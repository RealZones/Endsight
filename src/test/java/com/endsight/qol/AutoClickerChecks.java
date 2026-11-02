package com.endsight.qol;

import com.endsight.ui.Keybinds;
import com.endsight.ui.Setting;

/** Feature identity and controls use the normal module/config path. */
public final class AutoClickerChecks {
    public static void main(String[] args) {
        var module = AutoClicker.module();
        check(module.id().equals("qol.autoclicker") && module.category().equals("General"), "normal feature identity");
        check(!module.isEnabled(), "off by default");
        check(Keybinds.moduleId(module).equals("module.qol.autoclicker"), "normal module key binding");
        Setting.Slider min = (Setting.Slider) module.settings().getFirst();
        Setting.Slider max = (Setting.Slider) module.settings().getLast();
        check(min.get().getAsDouble() == 6 && max.get().getAsDouble() == 9, "existing default CPS preserved");
        check(min.min() == 1 && max.max() == 20 && min.step() == .5 && max.step() == .5, "existing slider bounds preserved");
        min.set().accept(12);
        check(min.get().getAsDouble() == 9, "minimum cannot exceed maximum");
        max.set().accept(2);
        check(max.get().getAsDouble() == 9, "maximum cannot fall below minimum");
        min.set().accept(6);
        max.set().accept(9);
        module.setEnabled().accept(true);
        check(AutoClicker.enabled() && (boolean) AutoClicker.settingsSnapshot().get("enabled"), "feature state is shared");
        AutoClicker.module().setEnabled().accept(false);
        check(!module.isEnabled(), "module views cannot create duplicate clickers");
        System.out.println("Auto Clicker feature checks passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
