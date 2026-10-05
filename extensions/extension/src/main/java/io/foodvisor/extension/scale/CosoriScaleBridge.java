package io.foodvisor.extension.scale;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * BLE transport for the Cosori CNS-R101S "COSORI Nutrition Scale".
 *
 * <p>Verified GATT layout (2026-10-05):
 * <ul>
 *     <li>Service {@code 00001910-0000-1000-8000-00805f9b34fb}</li>
 *     <li>Notify  {@code 00002c12-...} (measurement stream)</li>
 *     <li>Write   {@code 00002c11-...} (commands, e.g. set unit / tare)</li>
 *     <li>Read    {@code 00002c10-...}</li>
 * </ul>
 *
 * <p>The scale streams measurements as soon as notifications are enabled; no
 * handshake is required. The notification must survive a not-yet-bonded
 * connection: pairing does not complete ({@code Bonded: no}) but enabling
 * notifications still works.
 */
public final class CosoriScaleBridge {

    private static final String TAG = "CosoriScale";

    /** Advertised name is exactly "COSORI Nutrition Scale". */
    private static final String DEVICE_NAME_PREFIX = "COSORI";

    private static final UUID SERVICE_UUID =
            UUID.fromString("00001910-0000-1000-8000-00805f9b34fb");

    private static final UUID NOTIFY_CHARACTERISTIC_UUID =
            UUID.fromString("00002c12-0000-1000-8000-00805f9b34fb");

    private static final UUID COMMAND_CHARACTERISTIC_UUID =
            UUID.fromString("00002c11-0000-1000-8000-00805f9b34fb");

    private static final UUID CLIENT_CHARACTERISTIC_CONFIG =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static final int REQUEST_BLE_PERMISSIONS = 0x0F00;

    private static final String MACRO_FOOD_TYPE =
            "io.foodvisor.core.data.entity.MacroFoodAndFoodInfo";

    /** Give up scanning after this long if the scale is not found. */
    private static final long SCAN_TIMEOUT_MS = 30_000L;

    /** Tracks views that already received the scale button. */
    private static final WeakHashMap<View, Boolean> ATTACHED_VIEWS = new WeakHashMap<>();

    private static volatile BluetoothLeScanner scanner;
    private static volatile ScanCallback scanCallback;
    private static volatile BluetoothGatt gatt;
    private static volatile WeightCallback callback;
    private static volatile Button activeButton;
    private static volatile View activeRoot;
    private static volatile Runnable scanTimeout;

    private static volatile boolean itemPresent;
    private static volatile boolean connected;
    private static volatile int lastStableGrams = -1;
    private static volatile int lastQuantitySent = -1;
    private static volatile Object pickerViewModel;
    private static final java.util.List<Runnable> PENDING_NUTRITION = new java.util.ArrayList<>();

    private CosoriScaleBridge() {
    }

    /** Captured from the quantity picker ViewModel constructor. */
    public static void setViewModel(Object viewModel) {
        pickerViewModel = viewModel;
        Log.i(TAG, "setViewModel: " + (viewModel == null ? "null" : viewModel.getClass().getName()));
    }

    /**
     * Entry point injected into Foodvisor's quantity picker bottom sheet
     * (`QuantityPickerBottomSheet.onViewCreated`).
     *
     * <p>Adds a "scale" button to the sheet. When tapped it connects to the
     * CNS-R101S and writes the measured weight into the sheet's quantity field.
     * The field's existing text watcher then updates Foodvisor's view model.
     *
     * @param root the bottom sheet's root view (the {@code View} passed to
     *             {@code onViewCreated}).
     */
    public static void attach(View root) {
        if (root == null) {
            return;
        }
        synchronized (ATTACHED_VIEWS) {
            if (ATTACHED_VIEWS.containsKey(root)) {
                return;
            }
            ATTACHED_VIEWS.put(root, Boolean.TRUE);
        }

        Context context = root.getContext();
        int primary = resolveThemeColor(context, android.R.attr.colorPrimary, 0xFF1B3A57);

        // Borderless text button in the app's primary colour, appended to the
        // quantity/unit row (compact, no outline) so it blends in.
        Button button = new Button(context, null, android.R.attr.borderlessButtonStyle);
        button.setText("Waage");
        button.setAllCaps(false);
        button.setTextSize(14f);
        button.setTextColor(primary);
        button.setGravity(Gravity.CENTER);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(root, 10), dp(root, 2), dp(root, 10), dp(root, 2));
        button.setOnClickListener(v -> onScaleButtonClicked(root, button));

        ViewGroup target = null;
        if (root instanceof ViewGroup) {
            ViewGroup card = (ViewGroup) root;
            for (int i = 0; i < card.getChildCount(); i++) {
                View child = card.getChildAt(i);
                if (child instanceof ViewGroup && !(child instanceof EditText)) {
                    target = (ViewGroup) child;
                    break;
                }
            }
            if (target == null) {
                target = card;
            }
        }
        if (target != null) {
            // Adopt the app's typeface from an existing text view in the row.
            for (int i = 0; i < target.getChildCount(); i++) {
                View child = target.getChildAt(i);
                if (child instanceof android.widget.TextView) {
                    button.setTypeface(((android.widget.TextView) child).getTypeface());
                    break;
                }
            }

            // Match the divider that already sits between the quantity and unit.
            Drawable existingDivider = null;
            for (int i = 0; i < target.getChildCount(); i++) {
                View child = target.getChildAt(i);
                if (child.getClass() == View.class && child.getBackground() != null) {
                    existingDivider = child.getBackground();
                    break;
                }
            }

            View divider = new View(context);
            LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                    dp(root, 1), ViewGroup.LayoutParams.MATCH_PARENT);
            dividerParams.topMargin = dp(root, 4);
            dividerParams.bottomMargin = dp(root, 4);
            divider.setLayoutParams(dividerParams);
            if (existingDivider != null && existingDivider.getConstantState() != null) {
                divider.setBackground(existingDivider.getConstantState().newDrawable());
            } else {
                divider.setBackgroundColor(0x1F000000);
            }
            target.addView(divider);
            target.addView(button);
            Log.i(TAG, "attach: scale button added to " + target.getClass().getName());
        } else {
            Log.w(TAG, "attach: root view is not a ViewGroup, cannot add button");
        }

        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                cancelScanTimeout();
                stopScan();
            }
        });
    }

    private static void onScaleButtonClicked(View root, Button button) {
        final Context context = root.getContext();
        Activity activity = findActivity(context);

        if (!hasBlePermissions(context)) {
            if (activity != null) {
                activity.requestPermissions(requiredBlePermissions(), REQUEST_BLE_PERMISSIONS);
            }
            toast(context, "Bluetooth-Berechtigung erteilen ...");
            // The permission dialog interrupts the tap; start scanning once the
            // user has granted the permission.
            MAIN.postDelayed(() -> {
                if (hasBlePermissions(context)) {
                    beginScan(root, button);
                }
            }, 2500);
            return;
        }

        beginScan(root, button);
    }

    private static void beginScan(View root, final Button button) {
        final Context context = root.getContext();
        // The GATT callback uses these statics so a kept-alive connection can
        // write into the currently open sheet instead of a stale view.
        activeRoot = root;
        activeButton = button;

        if (connected && gatt != null) {
            setButtonState(button, "Verbunden", false);
            // The scale only emits on change; re-apply the last stable reading
            // and refresh the nutrition display when the sheet is reopened.
            if (lastStableGrams > 0) {
                setQuantityText(root, lastStableGrams);
            }
            scheduleNutrition(root);
            toast(context, "Waage verbunden");
            return;
        }

        cancelScanTimeout();
        setButtonState(button, "Suche ...", false);
        toast(context, "Suche Waage ...");

        Runnable timeout = () -> {
            scanTimeout = null;
            stopScan();
            setButtonState(activeButton, "Waage", true);
            toast(context, "Waage nicht gefunden");
        };
        scanTimeout = timeout;
        MAIN.postDelayed(timeout, SCAN_TIMEOUT_MS);

        startScan(context, new WeightCallback() {
            @Override
            public void onWeight(int grams, boolean settled) {
                if (grams <= 0) {
                    // Weight removed: the next item needs fresh nutrition.
                    itemPresent = false;
                    lastQuantitySent = -1;
                    cancelPendingNutrition();
                    return;
                }
                // Only accept stable readings; removing the food therefore does
                // not clear the already entered amount.
                if (!settled) {
                    return;
                }
                cancelScanTimeout();
                setButtonState(activeButton, "Verbunden", false);
                lastStableGrams = grams;
                setQuantityText(activeRoot, grams);
                if (!itemPresent || grams != lastQuantitySent) {
                    itemPresent = true;
                    lastQuantitySent = grams;
                    // Foodvisor recomputes the nutritional score for the new
                    // quantity asynchronously, so send the nutrition with a few
                    // delays to end up with the correct values.
                    scheduleNutrition(activeRoot);
                }
            }

            @Override
            public void onError(String message) {
                cancelScanTimeout();
                setButtonState(activeButton, "Waage", true);
                toast(activeRoot != null ? activeRoot.getContext() : context, message);
            }

            @Override
            public void onDisconnected() {
                setButtonState(activeButton, "Waage", true);
            }
        });
    }

    private static void cancelScanTimeout() {
        Runnable timeout = scanTimeout;
        scanTimeout = null;
        if (timeout != null) {
            MAIN.removeCallbacks(timeout);
        }
    }

    private static final long[] NUTRITION_DELAYS_MS = {300L, 900L, 1600L};

    private static void scheduleNutrition(View root) {
        cancelPendingNutrition();
        final View target = root;
        for (long delay : NUTRITION_DELAYS_MS) {
            Runnable runnable = () -> sendNutrition(target);
            synchronized (PENDING_NUTRITION) {
                PENDING_NUTRITION.add(runnable);
            }
            MAIN.postDelayed(runnable, delay);
        }
    }

    private static void cancelPendingNutrition() {
        synchronized (PENDING_NUTRITION) {
            for (Runnable runnable : PENDING_NUTRITION) {
                MAIN.removeCallbacks(runnable);
            }
            PENDING_NUTRITION.clear();
        }
    }

    private static void setButtonState(final Button button, final String text, final boolean enabled) {
        if (button == null) {
            return;
        }
        MAIN.post(() -> {
            button.setText(text);
            button.setEnabled(enabled);
        });
    }

    private static void setQuantityText(View root, int grams) {
        EditText editText = findFirstEditText(root);
        if (editText == null) {
            toast(root.getContext(), "Mengenfeld nicht gefunden");
            return;
        }
        String value = String.valueOf(grams);
        if (value.contentEquals(editText.getText())) {
            return;
        }
        editText.post(() -> {
            editText.setText(value);
            editText.setSelection(value.length());
        });
    }

    private static int dp(View view, int value) {
        return Math.round(value * view.getResources().getDisplayMetrics().density);
    }

    private static int resolveThemeColor(Context context, int attribute, int fallback) {
        TypedValue value = new TypedValue();
        if (context.getTheme().resolveAttribute(attribute, value, true)) {
            if (value.resourceId != 0) {
                return context.getColor(value.resourceId);
            }
            return value.data;
        }
        return fallback;
    }

    private static EditText findFirstEditText(View view) {
        if (view instanceof EditText) {
            return (EditText) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                EditText found = findFirstEditText(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static Activity findActivity(Context context) {
        Context current = context;
        while (current instanceof ContextWrapper) {
            if (current instanceof Activity) {
                return (Activity) current;
            }
            current = ((ContextWrapper) current).getBaseContext();
        }
        return null;
    }

    private static String[] requiredBlePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return new String[]{
                    "android.permission.BLUETOOTH_SCAN",
                    "android.permission.BLUETOOTH_CONNECT",
            };
        }
        return new String[]{"android.permission.ACCESS_FINE_LOCATION"};
    }

    private static boolean hasBlePermissions(Context context) {
        for (String permission : requiredBlePermissions()) {
            if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private static void toast(Context context, String message) {
        MAIN.post(() -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show());
    }

    @SuppressLint("MissingPermission")
    public static void startScan(Context context, WeightCallback weightCallback) {
        callback = weightCallback;

        BluetoothManager manager =
                (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            fail("Bluetooth is disabled");
            return;
        }

        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            fail("Bluetooth LE scanner unavailable");
            return;
        }

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        Log.i(TAG, "startScan: enabled=" + adapter.isEnabled());

        scanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                if (result.getDevice() == null) {
                    return;
                }
                String name = result.getDevice().getName();
                if (name == null && result.getScanRecord() != null) {
                    name = result.getScanRecord().getDeviceName();
                }
                Log.i(TAG, "scan result: " + result.getDevice().getAddress()
                        + " name=" + name + " rssi=" + result.getRssi());
                if (!isCosoriScale(result, name)) {
                    return;
                }
                Log.i(TAG, "found Cosori scale: " + result.getDevice().getAddress());
                stopScan();
                connect(context, result.getDevice().getAddress());
            }

            @Override
            public void onScanFailed(int errorCode) {
                fail("Scan failed: " + errorCode);
            }
        };

        try {
            scanner.startScan(null, settings, scanCallback);
            Log.i(TAG, "scan started");
        } catch (Exception e) {
            fail("startScan failed: " + e);
        }
    }

    private static boolean isCosoriScale(ScanResult result, String name) {
        if (name != null && name.toUpperCase().startsWith(DEVICE_NAME_PREFIX)) {
            return true;
        }
        if (result.getScanRecord() != null && result.getScanRecord().getServiceUuids() != null) {
            for (android.os.ParcelUuid parcelUuid : result.getScanRecord().getServiceUuids()) {
                if (SERVICE_UUID.equals(parcelUuid.getUuid())) {
                    return true;
                }
            }
        }
        return false;
    }

    @SuppressLint("MissingPermission")
    public static void stopScan() {
        BluetoothLeScanner current = scanner;
        ScanCallback currentCallback = scanCallback;
        scanner = null;
        scanCallback = null;
        if (current != null && currentCallback != null) {
            try {
                current.stopScan(currentCallback);
            } catch (Exception e) {
                Log.w(TAG, "stopScan failed", e);
            }
        }
    }

    @SuppressLint("MissingPermission")
    public static void connect(Context context, String address) {
        BluetoothManager manager =
                (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || address == null) {
            fail("Cannot connect to scale");
            return;
        }

        connected = false;
        gatt = adapter.getRemoteDevice(address)
                .connectGatt(context, false, new BluetoothGattCallback() {
                    @Override
                    public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
                        Log.i(TAG, "onConnectionStateChange status=" + status + " newState=" + newState);
                        if (newState == BluetoothProfile.STATE_CONNECTED) {
                            connected = true;
                            cancelScanTimeout();
                            setButtonState(activeButton, "Verbunden", false);
                            g.discoverServices();
                        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                            connected = false;
                            closeGatt();
                            if (callback != null) {
                                MAIN.post(() -> callback.onDisconnected());
                            }
                        }
                    }

                    @Override
                    public void onServicesDiscovered(BluetoothGatt g, int status) {
                        Log.i(TAG, "onServicesDiscovered status=" + status);
                        BluetoothGattService service = g.getService(SERVICE_UUID);
                        if (service == null) {
                            fail("Scale service not found");
                            return;
                        }
                        BluetoothGattCharacteristic characteristic =
                                service.getCharacteristic(NOTIFY_CHARACTERISTIC_UUID);
                        if (characteristic == null) {
                            fail("Scale notify characteristic not found");
                            return;
                        }
                        enableNotifications(g, characteristic);
                    }

                    @Override
                    public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int status) {
                        Log.i(TAG, "onDescriptorWrite status=" + status + " uuid=" + descriptor.getUuid());
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            fail("Notification enable failed: " + status);
                        }
                    }

                    @Override
                    public void onCharacteristicChanged(BluetoothGatt g,
                                                        BluetoothGattCharacteristic characteristic) {
                        byte[] value = characteristic.getValue();
                        CosoriCnsR101sProtocol.Measurement m =
                                CosoriCnsR101sProtocol.parse(value);
                        Log.i(TAG, "notification " + bytesToHex(value) + " -> "
                                + (m.valid ? (m.grams + "g settled=" + m.settled) : "invalid"));
                        if (m.valid && callback != null) {
                            MAIN.post(() -> callback.onWeight(Math.round(m.grams), m.settled));
                        }
                    }
                });
    }

    private static String bytesToHex(byte[] data) {
        if (data == null) {
            return "null";
        }
        StringBuilder builder = new StringBuilder(data.length * 2);
        for (byte b : data) {
            builder.append(String.format("%02x", b & 0xFF));
        }
        return builder.toString();
    }

    @SuppressLint("MissingPermission")
    private static void enableNotifications(BluetoothGatt g,
                                            BluetoothGattCharacteristic characteristic) {
        boolean notified = g.setCharacteristicNotification(characteristic, true);
        Log.i(TAG, "setCharacteristicNotification=" + notified);
        BluetoothGattDescriptor descriptor =
                characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG);
        if (descriptor == null) {
            fail("Notification descriptor missing");
            return;
        }
        descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        boolean requested = g.writeDescriptor(descriptor);
        Log.i(TAG, "writeDescriptor requested=" + requested);
    }

    /** Commands are sent in chunks because the scale negotiates a 23-byte MTU. */
    private static final int WRITE_CHUNK_SIZE = 20;

    /**
     * Sends a raw command frame to the scale's write characteristic
     * ({@code 0x2c11}). Long frames (e.g. {@code SET_NUTRITION}) are split into
     * 20-byte write-without-response chunks, matching the VeSync app.
     */
    @SuppressLint("MissingPermission")
    public static boolean writeCommand(byte[] frame) {
        final BluetoothGatt g = gatt;
        if (g == null || frame == null || frame.length == 0) {
            return false;
        }
        final BluetoothGattService service = g.getService(SERVICE_UUID);
        if (service == null) {
            return false;
        }
        final BluetoothGattCharacteristic characteristic =
                service.getCharacteristic(COMMAND_CHARACTERISTIC_UUID);
        if (characteristic == null) {
            return false;
        }
        characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);

        final int chunkCount = (frame.length + WRITE_CHUNK_SIZE - 1) / WRITE_CHUNK_SIZE;
        final int[] index = {0};
        Runnable sender = new Runnable() {
            @Override
            public void run() {
                if (index[0] >= chunkCount || gatt == null) {
                    return;
                }
                int start = index[0] * WRITE_CHUNK_SIZE;
                int end = Math.min(frame.length, start + WRITE_CHUNK_SIZE);
                byte[] chunk = java.util.Arrays.copyOfRange(frame, start, end);
                index[0]++;
                characteristic.setValue(chunk);
                boolean ok = g.writeCharacteristic(characteristic);
                Log.i(TAG, "writeChunk " + index[0] + "/" + chunkCount
                        + " len=" + chunk.length + " ok=" + ok);
                MAIN.postDelayed(this, 40);
            }
        };
        MAIN.post(sender);
        return true;
    }

    public static boolean setUnitGrams() {
        return writeCommand(CosoriCnsR101sProtocol.setUnit(CosoriCnsR101sProtocol.UNIT_GRAM));
    }

    public static boolean tare() {
        return writeCommand(CosoriCnsR101sProtocol.setTare(true));
    }

    @SuppressLint("MissingPermission")
    public static void disconnect() {
        closeGatt();
    }

    @SuppressLint("MissingPermission")
    private static void closeGatt() {
        BluetoothGatt current = gatt;
        gatt = null;
        if (current != null) {
            try {
                current.close();
            } catch (Exception e) {
                Log.w(TAG, "closeGatt failed", e);
            }
        }
    }

    /**
     * Reads the current nutrition from Foodvisor's quantity picker and sends it
     * to the scale display (`SET_NUTRITION`). Best-effort: unknown/obfuscated
     * structure fails silently (logged only).
     */
    private static void sendNutrition(View root) {
        try {
            Object macroFood = findMacroFoodFromActivity(findActivity(root.getContext()));
            if (macroFood == null) {
                macroFood = findFieldOfType(pickerViewModel, MACRO_FOOD_TYPE);
            }
            Log.i(TAG, "sendNutrition: vm=" + (pickerViewModel != null)
                    + " macroFood=" + (macroFood != null));
            if (macroFood == null) {
                return;
            }
            Object score = macroFood.getClass().getMethod("getNutritionalScore").invoke(macroFood);
            int[] values = extractNutrition(score);
            boolean ok = writeCommand(CosoriCnsR101sProtocol.setNutrition(values));
            Log.i(TAG, "sendNutrition: sent=" + ok + " values=" + java.util.Arrays.toString(values));
        } catch (Throwable t) {
            Log.w(TAG, "sendNutrition failed", t);
        }
    }

    /**
     * Searches the activity's ViewModelStore for a non-null
     * {@code MacroFoodAndFoodInfo}. This reaches the detail sheet's ViewModel,
     * which is not necessarily the quantity picker ViewModel.
     */
    private static Object findMacroFoodFromActivity(Activity activity) {
        if (activity == null) {
            return null;
        }
        try {
            Object store = activity.getClass().getMethod("getViewModelStore").invoke(activity);
            if (store == null) {
                return null;
            }
            Object map = null;
            for (Field field : store.getClass().getDeclaredFields()) {
                if (Map.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    map = field.get(store);
                    break;
                }
            }
            if (!(map instanceof Map)) {
                return null;
            }
            for (Object viewModel : ((Map<?, ?>) map).values()) {
                Object macroFood = findFieldOfType(viewModel, MACRO_FOOD_TYPE);
                if (macroFood != null) {
                    Log.i(TAG, "findMacroFood: " + viewModel.getClass().getName());
                    return macroFood;
                }
            }
        } catch (Throwable t) {
            Log.i(TAG, "findMacroFoodFromActivity failed: " + t);
        }
        return null;
    }

    private static Object findFieldOfType(Object target, String typeName) throws Exception {
        for (Class<?> type = target.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (field.getType().getName().equals(typeName)) {
                    field.setAccessible(true);
                    return field.get(target);
                }
            }
        }
        return null;
    }

    // Foodvisor's Macro map stores energy (kcal); grams = energy / coefficient.
    private static final double KCAL_PER_G_PROTEIN = 4.0;
    private static final double KCAL_PER_G_FAT = 9.0;
    private static final double KCAL_PER_G_CARBS = 4.0;
    private static final double KCAL_PER_G_FIBER = 2.0;

    private static int[] extractNutrition(Object score) throws Exception {
        double calories = ((Number) score.getClass().getMethod("getCalories").invoke(score)).doubleValue();
        Map<?, ?> macros = (Map<?, ?>) score.getClass().getMethod("getMacros").invoke(score);
        Map<?, ?> micros = (Map<?, ?>) score.getClass().getMethod("getMicros").invoke(score);

        double proteinsKcal = enumMapValue(macros, "Proteins");
        double lipidsKcal = enumMapValue(macros, "Lipids");
        double carbsKcal = enumMapValue(macros, "Carbs");
        double fibersKcal = enumMapValue(macros, "Fibers");

        // Order expected by the scale (see CosoriCnsR101sProtocol#setNutrition).
        return new int[]{
                tenths(calories),
                tenths(lipidsKcal),                        // caloriesFromFat
                tenths(lipidsKcal / KCAL_PER_G_FAT),       // totalFat (g)
                tenths(enumMapValue(micros, "SatFat")),
                tenths(enumMapValue(micros, "TransFat")),
                tenths(enumMapValue(micros, "Cholesterol")),
                tenths(enumMapValue(micros, "Sodium")),
                tenths(enumMapValue(micros, "Potassium")),
                tenths(carbsKcal / KCAL_PER_G_CARBS),      // totalCarbs (g)
                tenths(fibersKcal / KCAL_PER_G_FIBER),     // dietaryFiber (g)
                tenths(enumMapValue(micros, "Sugars")),
                tenths(proteinsKcal / KCAL_PER_G_PROTEIN), // protein (g)
        };
    }

    private static int tenths(double value) {
        return (int) Math.round(value * 10.0);
    }

    private static double enumMapValue(Map<?, ?> map, String constantName) {
        if (map == null) {
            return 0.0;
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            Object key = entry.getKey();
            if (key instanceof Enum && ((Enum<?>) key).name().equals(constantName)
                    && entry.getValue() instanceof Number) {
                return ((Number) entry.getValue()).doubleValue();
            }
        }
        return 0.0;
    }

    private static void fail(String message) {
        Log.e(TAG, message);
        if (callback != null) {
            MAIN.post(() -> callback.onError(message));
        }
    }
}
