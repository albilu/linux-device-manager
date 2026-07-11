package org.ldm.core.action;

/** Outcome of a privileged device action. */
public record DeviceActionResult(Outcome outcome, String message) {

    /** Result categories a caller (UI) reacts to. */
    public enum Outcome {
        SUCCESS, AUTH_CANCELLED, FAILED, UNSUPPORTED
    }

    public static DeviceActionResult success() {
        return new DeviceActionResult(Outcome.SUCCESS, "");
    }

    public static DeviceActionResult authCancelled() {
        return new DeviceActionResult(Outcome.AUTH_CANCELLED, "Authentication was cancelled.");
    }

    public static DeviceActionResult failed(String message) {
        return new DeviceActionResult(Outcome.FAILED, message);
    }

    public static DeviceActionResult unsupported(String message) {
        return new DeviceActionResult(Outcome.UNSUPPORTED, message);
    }

    public boolean isSuccess() {
        return outcome == Outcome.SUCCESS;
    }
}
