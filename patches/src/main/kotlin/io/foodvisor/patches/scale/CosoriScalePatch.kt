package io.foodvisor.patches.scale

import app.revanced.patcher.extensions.InstructionExtensions.addInstructions
import app.revanced.patcher.fingerprint
import app.revanced.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode

/**
 * Matches the `FoodUnitPickerView(Context, AttributeSet)` constructor.
 *
 * `FoodUnitPickerView` is referenced by name in the layout XML, so R8 keeps the
 * class name stable (unlike the obfuscated `QuantityPickerBottomSheet`). Its
 * single constructor inflates `view_food_unit_picker` and creates the quantity
 * `TextInputEditText`, so after the constructor returns the view is fully
 * initialised and children exist.
 */
private val foodUnitPickerViewFingerprint = fingerprint {
    returns("V")
    parameters("Landroid/content/Context;", "Landroid/util/AttributeSet;")
    custom { method, classDef ->
        method.name == "<init>" &&
            classDef.type == "Lio/foodvisor/mealxp/view/food/FoodUnitPickerView;"
    }
}

/**
 * Matches the `QuantityPickerViewModel` constructor.
 *
 * The class is obfuscated, but it is the only class in the quantity picker
 * package that holds a `MacroFoodAndFoodInfo`. Matching on that field type keeps
 * the fingerprint stable across Foodvisor versions.
 */
private val quantityPickerViewModelFingerprint = fingerprint {
    returns("V")
    custom { method, classDef ->
        method.name == "<init>" &&
            classDef.type.startsWith("Lio/foodvisor/mealxp/view/search/overlay/quantitypicker/") &&
            !classDef.type.contains("$") &&
            classDef.superclass?.startsWith("Landroidx/lifecycle/") == true &&
            classDef.fields.any { it.type == "Lio/foodvisor/core/data/entity/MacroFoodAndFoodInfo;" }
    }
}

/**
 * EXPERIMENTAL.
 *
 * Foodvisor has no Bluetooth kitchen scale feature of its own. This patch adds a
 * "scale" button to the quantity picker (`FoodUnitPickerView`) that connects to
 * the Cosori CNS-R101S, writes the measured weight into the quantity field and
 * pushes the current nutrition values to the scale display (`SET_NUTRITION`).
 *
 * The scale protocol (docs/PROTOCOL.md) and GATT transport are implemented in
 * `io.foodvisor.extension.scale`. The manifest permissions are added by
 * [cosoriScalePermissionsPatch].
 */
@Suppress("unused")
val cosoriScalePatch = bytecodePatch(
    name = "Cosori CNS-R101S scale support",
    description = "Experimental bridge that connects a Cosori CNS-R101S (VeSync) BLE kitchen scale, feeds its weight into Foodvisor's quantity picker and pushes nutrition values to the scale display.",
) {
    compatibleWith("io.foodvisor.foodvisor"("7.5.6"))

    dependsOn(cosoriScalePermissionsPatch)

    extendWith("extensions/foodvisor-scale.rve")

    execute {
        // Add the "scale" button right before the normal return-void of the
        // FoodUnitPickerView constructor (`this` is fully initialised there).
        val constructor = foodUnitPickerViewFingerprint.method
        val implementation = constructor.implementation
            ?: throw IllegalStateException("FoodUnitPickerView constructor has no implementation")
        val instructions = implementation.instructions
        val insertIndex = instructions.indexOfLast { it.opcode == Opcode.RETURN_VOID }
        check(insertIndex >= 0) { "FoodUnitPickerView constructor has no return-void" }
        constructor.addInstructions(
            insertIndex,
            """
                invoke-static {p0}, Lio/foodvisor/extension/scale/CosoriScaleBridge;->attach(Landroid/view/View;)V
            """,
        )

        // Capture the quantity picker ViewModel so the current
        // MacroFoodAndFoodInfo (and thus the nutritional score) can be read.
        val viewModelConstructor = quantityPickerViewModelFingerprint.method
        val viewModelImplementation = viewModelConstructor.implementation
            ?: throw IllegalStateException("QuantityPickerViewModel constructor has no implementation")
        val viewModelInstructions = viewModelImplementation.instructions
        val viewModelInsertIndex = viewModelInstructions.indexOfLast { it.opcode == Opcode.RETURN_VOID }
        check(viewModelInsertIndex >= 0) { "QuantityPickerViewModel constructor has no return-void" }
        viewModelConstructor.addInstructions(
            viewModelInsertIndex,
            """
                invoke-static {p0}, Lio/foodvisor/extension/scale/CosoriScaleBridge;->setViewModel(Ljava/lang/Object;)V
            """,
        )
    }
}
