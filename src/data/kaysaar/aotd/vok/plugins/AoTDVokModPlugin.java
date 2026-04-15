package data.kaysaar.aotd.vok.plugins;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import data.kaysaar.aotd.vok.campaign.econ.globalproduction.models.GPManager;
import data.kaysaar.aotd.vok.misc.AoTDMisc;

import java.util.ArrayList;
import java.util.Random;

public class AoTDVokModPlugin extends com.fs.starfarer.api.BaseModPlugin {

    @Override
    public void onNewGameAfterEconomyLoad() {
        // --- 1. PREVENT DOUBLE SPAWN ---
        if (Global.getSector().getMemoryWithoutUpdate().contains("$aotd_vok_megastructures_spawned")) {
            return;
        }
        Global.getSector().getMemoryWithoutUpdate().set("$aotd_vok_megastructures_spawned", true);

        // --- 2. FIND SYSTEM ---
        GPManager.getInstance().getTotalResources();
        ArrayList<StarSystemAPI> systems = AoTDMisc.getStarSystemWithMegastructure("coronal_tap");
        ArrayList<StarSystemAPI> valid = new ArrayList<>();

        for (StarSystemAPI s : systems) {
            if (s != null && s.getPlanets().size() > 2) {
                valid.add(s);
            }
        }

        if (valid.isEmpty()) return;

        StarSystemAPI chosen = valid.get(new Random().nextInt(valid.size()));
        ArrayList<StarSystemAPI> one = new ArrayList<>();
        one.add(chosen);

        // Save to memory for the script to find
        Global.getSector().getMemoryWithoutUpdate().set("$aotd_chosen_sys", chosen.getId());

        // --- 3. RESTORE YOUR SPAWNING CODE ---
        AoTDDataInserter inserter = new AoTDDataInserter();
        inserter.spawnPluto(one);
        inserter.spawnNidavleir(one);

        // --- 4. START THE HIJACK SCRIPT ---
        // We use a script because Nexerelin moves the player multiple times during startup
        Global.getSector().addScript(new OwnFactionRelocationScript(chosen.getId()));
    }

    public static class OwnFactionRelocationScript implements EveryFrameScript {
        private String targetSystemId;
        private boolean done = false;
        private int frames = 0;

        public OwnFactionRelocationScript(String targetSystemId) {
            this.targetSystemId = targetSystemId;
        }

        @Override
        public boolean isDone() { return done; }

        @Override
        public boolean runWhilePaused() { return true; }

        @Override
        public void advance(float amount) {
            if (done) return;

            // Wait 2 frames to ensure Nexerelin has finished its own "onNewGame" setup
            frames++;
            if (frames < 2) return;

            MarketAPI playerMarket = null;
            for (MarketAPI market : Global.getSector().getEconomy().getMarketsCopy()) {
                if (market.isPlayerOwned()) {
                    playerMarket = market;
                    break;
                }
            }

            if (playerMarket == null) return;

            StarSystemAPI targetSystem = Global.getSector().getStarSystem(targetSystemId);
            if (targetSystem == null) {
                done = true;
                return;
            }

            // Find a planet in the target system (not a star, not a gas giant)
            PlanetAPI targetPlanet = null;
            for (PlanetAPI p : targetSystem.getPlanets()) {
                if (!p.isStar() && !p.isGasGiant()) {
                    targetPlanet = p;
                    break;
                }
            }

            if (targetPlanet != null) {
                // MOVE MARKET
                PlanetAPI oldPlanet = (PlanetAPI) playerMarket.getPrimaryEntity();
                if (oldPlanet != null && oldPlanet != targetPlanet) {
                    oldPlanet.setMarket(null);
                    oldPlanet.setFaction("neutral");
                }

                targetPlanet.setMarket(playerMarket);
                playerMarket.setPrimaryEntity(targetPlanet);
                playerMarket.setName(targetPlanet.getName());

                // MOVE PLAYER FLEET
                CampaignFleetAPI pf = Global.getSector().getPlayerFleet();
                if (pf != null) {
                    pf.getContainingLocation().removeEntity(pf);
                    targetSystem.addEntity(pf);
                    pf.setLocation(targetPlanet.getLocation().x, targetPlanet.getLocation().y);

                    // FORCE CAMERA
                    Global.getSector().setCurrentLocation(targetSystem);
                }

                // HIJACK NEXERELIN MEMORY
                Global.getSector().getMemoryWithoutUpdate().set("$nex_player_home_system", targetSystem.getId());

                // FINAL LOG
                Global.getLogger(this.getClass()).info("AoTD VOK: Forced Own Faction start to " + targetSystem.getName());
                done = true;
            }
        }
    }
}