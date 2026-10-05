package io.foodvisor.extension.scale;

/**
 * Protocol codec for the Cosori CNS-R101S "COSORI Nutrition Scale".
 *
 * <p>Wire format (confirmed against real hardware, 2026-10-05):
 *
 * <pre>
 *   FE EF ?? ?? | type | len | payload[len] | checksum
 *   checksum = (type + len + payload bytes) &amp; 0xFF
 * </pre>
 *
 * <p>Bytes 2-3 of the header differ between models
 * ({@code 00 84} on the CNS-R101S, {@code C0 A2} on the Etekcity ESN00) and
 * must not be validated. Only {@code FE EF} is constant.
 *
 * <p>Measurement ({@code type = 0xD0}), 5-byte payload:
 *
 * <pre>
 *   [0]   sign     0x00 positive / 0x01 negative
 *   [1-2] weight   uint16, big-endian, unit = 0.1 g
 *   [3]   unit     0x00 g, 0x02 ml, 0x03 floz, 0x04 ml milk, 0x05 floz milk,
 *                  0x06 oz, 0x01 lb:oz
 *   [4]   settled  0x00 measuring / 0x01 stable
 * </pre>
 *
 * Verified samples: empty = {@code feef0084 d0 05 0000000001 d6} = 0.0 g,
 * 1 kg flour bag = {@code feef0084 d0 05 0026480001 44} = 980.0 g (matches
 * the scale's own display).
 */
public final class CosoriCnsR101sProtocol {

    /** Protocol verified against real hardware. */
    public static final boolean PROTOCOL_IMPLEMENTED = true;

    public static final byte HEADER_0 = (byte) 0xFE;
    public static final byte HEADER_1 = (byte) 0xEF;

    public static final int TYPE_SET_UNIT = 0xC0;
    public static final int TYPE_SET_TARE = 0xC1;
    public static final int TYPE_SET_NUTRITION = 0xC2;
    public static final int TYPE_SET_AUTO_OFF = 0xC4;
    public static final int TYPE_MEASUREMENT = 0xD0;
    public static final int TYPE_UNIT_STATE = 0xD1;
    public static final int TYPE_TARE_STATE = 0xD3;
    public static final int TYPE_AUTO_OFF_STATE = 0xD5;
    public static final int TYPE_ERROR_STATE = 0xE0;
    public static final int TYPE_ITEM_STATE = 0xE4;

    public static final int UNIT_GRAM = 0x00;
    public static final int UNIT_POUND_OUNCE = 0x01;
    public static final int UNIT_MILLILITER_WATER = 0x02;
    public static final int UNIT_FLUID_OUNCE_WATER = 0x03;
    public static final int UNIT_MILLILITER_MILK = 0x04;
    public static final int UNIT_FLUID_OUNCE_MILK = 0x05;
    public static final int UNIT_OUNCE = 0x06;

    private CosoriCnsR101sProtocol() {
    }

    /** Result of {@link #parse(byte[])}. */
    public static final class Measurement {
        public final boolean valid;
        public final boolean settled;
        public final int unit;
        public final float grams;
        /** Raw weight value in 0.1 g units, signed. */
        public final int rawTenths;

        private Measurement(boolean valid, boolean settled, int unit, int rawTenths) {
            this.valid = valid;
            this.settled = settled;
            this.unit = unit;
            this.rawTenths = rawTenths;
            this.grams = rawTenths / 10f;
        }

        static Measurement invalid() {
            return new Measurement(false, false, UNIT_GRAM, 0);
        }
    }

    /** @return checksum byte over {@code data[offset .. offset+length-1]}. */
    public static int checksum(byte[] data, int offset, int length) {
        int sum = 0;
        for (int i = offset; i < offset + length; i++) {
            sum += data[i] & 0xFF;
        }
        return sum & 0xFF;
    }

    /**
     * Parses a notification frame.
     *
     * @return a {@link Measurement}; check {@link Measurement#valid}. Non
     *         measurement frames (e.g. {@code 0xE4} ITEM_STATE) are returned as
     *         invalid.
     */
    public static Measurement parse(byte[] data) {
        if (data == null || data.length < 7) {
            return Measurement.invalid();
        }
        if (data[0] != HEADER_0 || data[1] != HEADER_1) {
            return Measurement.invalid();
        }

        final int type = data[4] & 0xFF;
        final int length = data[5] & 0xFF;
        if (data.length < 6 + length + 1) {
            return Measurement.invalid();
        }
        if (checksum(data, 4, 2 + length) != (data[6 + length] & 0xFF)) {
            return Measurement.invalid();
        }
        if (type != TYPE_MEASUREMENT || length < 5) {
            return Measurement.invalid();
        }

        final int sign = data[6] == 0 ? 1 : -1;
        final int raw = ((data[7] & 0xFF) << 8) | (data[8] & 0xFF);
        final int unit = data[9] & 0xFF;
        final boolean settled = data[10] != 0;

        return new Measurement(true, settled, unit, sign * raw);
    }

    /** @return true if the frame reports a stable measurement. */
    public static boolean isStable(byte[] data) {
        return parse(data).settled;
    }

    /** Builds a complete command frame with header and checksum. */
    public static byte[] buildFrame(int type, byte[] payload) {
        final byte[] body;
        if (payload == null) {
            body = new byte[2];
        } else {
            body = new byte[2 + payload.length];
            System.arraycopy(payload, 0, body, 2, payload.length);
        }
        body[0] = (byte) type;
        body[1] = (byte) (payload == null ? 0 : payload.length);

        final byte[] frame = new byte[4 + body.length + 1];
        frame[0] = HEADER_0;
        frame[1] = HEADER_1;
        frame[2] = 0x00;
        frame[3] = (byte) 0x84;
        System.arraycopy(body, 0, frame, 4, body.length);
        frame[frame.length - 1] = (byte) checksum(body, 0, body.length);
        return frame;
    }

    public static byte[] setUnit(int unit) {
        return buildFrame(TYPE_SET_UNIT, new byte[]{(byte) unit});
    }

    public static byte[] setTare(boolean reset) {
        return buildFrame(TYPE_SET_TARE, new byte[]{(byte) (reset ? 1 : 0)});
    }

    public static byte[] setAutoOff(int timeout) {
        return buildFrame(TYPE_SET_AUTO_OFF, new byte[]{(byte) timeout});
    }

    /**
     * Builds a {@code SET_NUTRITION} (0xC2) frame.
     *
     * <p>Payload is 12 values, each a 3-byte big-endian integer in 0.1 units,
     * in the order: calories, caloriesFromFat, totalFat, saturatedFat, transFat,
     * cholesterol, sodium, potassium, totalCarbs, dietaryFiber, sugars, protein.
     *
     * <p>UNVERIFIED: the byte order is inferred from the measurement packet; the
     * command side of the ESN00 family has not been confirmed on this device.
     *
     * @param valuesTenths 12 values already scaled by 10 (e.g. 980 g -> 9800).
     */
    public static byte[] setNutrition(int[] valuesTenths) {
        byte[] payload = new byte[36];
        for (int index = 0; index < 12; index++) {
            int value = index < valuesTenths.length ? valuesTenths[index] : 0;
            if (value < 0) {
                value = 0;
            }
            if (value > 0xFFFFF) {
                value = 0xFFFFF;
            }
            payload[index * 3] = (byte) ((value >> 16) & 0xFF);
            payload[index * 3 + 1] = (byte) ((value >> 8) & 0xFF);
            payload[index * 3 + 2] = (byte) (value & 0xFF);
        }
        return buildFrame(TYPE_SET_NUTRITION, payload);
    }
}
