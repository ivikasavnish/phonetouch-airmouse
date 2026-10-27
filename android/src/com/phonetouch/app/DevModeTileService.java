package com.phonetouch.app;

import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick Settings tile: one tap flips Developer options + USB debugging together. */
public class DevModeTileService extends TileService {
    @Override
    public void onStartListening() {
        refresh();
    }

    @Override
    public void onClick() {
        DevGuardService.setDevMode(this, !DevGuardService.isDevModeOn(this));
        refresh();
    }

    private void refresh() {
        Tile tile = getQsTile();
        if (tile == null) return;
        if (!DevGuardService.hasPermission(this)) {
            tile.setState(Tile.STATE_UNAVAILABLE);
        } else {
            tile.setState(DevGuardService.isDevModeOn(this) ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        }
        tile.updateTile();
    }
}
