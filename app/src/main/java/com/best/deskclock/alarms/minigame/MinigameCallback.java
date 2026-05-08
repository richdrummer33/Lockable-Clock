// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.alarms.minigame;

/**
 * Simple callback used by all three confirmation minigame dialogs.
 * <p>
 * Only {@link #onSuccess()} is invoked from the dialog — failure / dismiss silently keeps the
 * alarm alive, which is the intended "fail-safe" behaviour of every minigame.
 */
public interface MinigameCallback {
    /** The user successfully completed the minigame; proceed with the destructive action. */
    void onSuccess();
}
