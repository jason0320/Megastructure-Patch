package data.kaysaar.aotd.vok.plugins;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.MarketConditionAPI;
import com.fs.starfarer.api.impl.campaign.ids.Conditions;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.campaign.econ.SubmarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Industries;
import com.fs.starfarer.api.impl.campaign.ids.Submarkets;
import data.kaysaar.aotd.vok.campaign.econ.globalproduction.models.GPManager;
import data.kaysaar.aotd.vok.misc.AoTDMisc;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class AoTDVokModPlugin extends BaseModPlugin {

    private static final String SPAWN_FLAG = "$aotd_v_megastructures_spawned";

    @Override
    public void onNewGameAfterEconomyLoad() {
        if (Global.getSector().getMemoryWithoutUpdate().contains("$aotd_v_megastructures_spawned")) return;

        GPManager.getInstance().getTotalResources();

        ArrayList<StarSystemAPI> systems = AoTDMisc.getStarSystemWithMegastructure("coronal_tap");
        StarSystemAPI chosen = null;

        if (systems != null && !systems.isEmpty()) {
            ArrayList<StarSystemAPI> valid = new ArrayList<StarSystemAPI>();
            for (StarSystemAPI s : systems) {
                if (s != null && s.getPlanets() != null && s.getPlanets().size() > 2) {
                    valid.add(s);
                }
            }

            if (!valid.isEmpty()) {
                chosen = valid.get(new Random().nextInt(valid.size()));
            } else {
                chosen = systems.get(new Random().nextInt(systems.size()));
            }
        }

        if (chosen == null) {
            return;
        }

        Global.getSector().getMemoryWithoutUpdate().set("$aotd_v_megastructures_spawned", true);
        Global.getSector().getMemoryWithoutUpdate().set("$aotd_v_targetSystemId", chosen.getId());

        AoTDDataInserter inserter = new AoTDDataInserter();
        ArrayList<StarSystemAPI> list = new ArrayList<StarSystemAPI>();
        list.add(chosen);
        inserter.spawnPluto(list);
        inserter.spawnNidavleir(list);

        Global.getSector().addTransientScript(new OwnFactionRelocationScript(chosen.getId()));
    }

    public static class OwnFactionRelocationScript implements EveryFrameScript {
        private final String targetSystemId;
        private final com.fs.starfarer.api.util.IntervalUtil interval = new com.fs.starfarer.api.util.IntervalUtil(0.1f, 0.2f);
        private boolean done = false;
        private boolean relocated = false;

        public OwnFactionRelocationScript(String targetSystemId) {
            this.targetSystemId = targetSystemId;
        }

        @Override
        public boolean isDone() {
            return done;
        }

        @Override
        public boolean runWhilePaused() {
            return true;
        }

        @Override
        public void advance(float amount) {
            if (done) return;

            interval.advance(amount);
            if (!interval.intervalElapsed()) return;

            StarSystemAPI targetSystem = Global.getSector().getStarSystem(targetSystemId);
            if (targetSystem == null) return;

            PlanetAPI targetPlanet = findTargetPlanet(targetSystem);
            if (targetPlanet == null) return;

            CampaignFleetAPI playerFleet = Global.getSector().getPlayerFleet();
            if (playerFleet == null) return;

            // Wait until the game has actually finished placing the fleet.
            if (playerFleet.getContainingLocation() == null) return;

            if (!relocated) {
                MarketAPI playerMarket = findPlayerColony();

                if (playerMarket != null) {
                    SectorEntityToken oldEntity = playerMarket.getPrimaryEntity();
                    PlanetAPI oldPlanet = oldEntity instanceof PlanetAPI ? (PlanetAPI) oldEntity : null;
                    StarSystemAPI oldSystem = oldPlanet != null ? oldPlanet.getStarSystem() : null;

                    scrubSurveyData(oldSystem);
                    relocateColony(playerMarket, targetPlanet);
                }

                forceMovePlayerFleet(playerFleet, targetPlanet, targetSystem);
                relocated = true;
                return;
            }

            // One extra pass to prevent the start script from snapping it back.
            forceMovePlayerFleet(playerFleet, targetPlanet, targetSystem);
            done = true;
        }

        private void forceMovePlayerFleet(CampaignFleetAPI playerFleet, PlanetAPI targetPlanet, StarSystemAPI targetSystem) {
            if (playerFleet.getContainingLocation() != null) {
                playerFleet.getContainingLocation().removeEntity(playerFleet);
            }

            targetSystem.addEntity(playerFleet);
            playerFleet.setLocation(targetPlanet.getLocation().x + 100f, targetPlanet.getLocation().y + 100f);

            Global.getSector().setCurrentLocation(targetSystem);
            Global.getSector().getMemoryWithoutUpdate().set("$nex_startLocation", targetPlanet.getId());
        }

        private MarketAPI findPlayerColony() {
            for (MarketAPI market : Global.getSector().getEconomy().getMarketsCopy()) {
                if (market != null && market.isPlayerOwned()) {
                    return market;
                }
            }
            return null;
        }

        private PlanetAPI findTargetPlanet(StarSystemAPI system) {
            for (PlanetAPI planet : system.getPlanets()) {
                if (planet == null) continue;
                if (planet.isStar() || planet.isGasGiant()) continue;
                if (hasForbiddenAotdCondition(planet)) continue;
                return planet;
            }
            return null;
        }

        private boolean hasForbiddenAotdCondition(PlanetAPI planet) {
            MarketAPI market = planet.getMarket();
            if (market == null) return false;

            for (MarketConditionAPI condition : market.getConditions()) {
                if (condition == null) continue;
                String id = condition.getId();
                if (id != null && id.toLowerCase().startsWith("aotd_")) {
                    return true;
                }
            }
            return false;
        }

        private void scrubSurveyData(StarSystemAPI system) {
            if (system == null) return;

            for (PlanetAPI planet : system.getPlanets()) {
                if (planet == null) continue;

                MarketAPI market = planet.getMarket();
                if (market != null) {
                    market.setSurveyLevel(MarketAPI.SurveyLevel.NONE);
                }
            }
        }

        private void relocateColony(MarketAPI oldMarket, PlanetAPI targetPlanet) {
            // Remove any pre-existing market on the target so we don't inherit wrong ownership/state.
            MarketAPI existingNewMarket = targetPlanet.getMarket();
            List<MarketConditionAPI> existingNewConditions = existingNewMarket.getConditions();
            MarketAPI targetMarket = Global.getFactory().createMarket(
                    targetPlanet.getId() + "_aotd_colony",
                    targetPlanet.getName(),
                    oldMarket != null ? oldMarket.getSize() : 3
            );

            for (MarketConditionAPI existingNewCondition : existingNewConditions) {
                if (existingNewCondition == null) continue;

                String id = existingNewCondition.getId();
                if (id == null) continue;

                if (!targetMarket.hasCondition(id)) {
                    targetMarket.addCondition(id);
                }
            }

            targetMarket.setPrimaryEntity(targetPlanet);
            targetPlanet.setMarket(targetMarket);

            targetPlanet.setFaction(Factions.PLAYER);

            Global.getSector().getEconomy().addMarket(targetMarket, true);

            String playerFactionId = Factions.PLAYER;
            targetMarket.setFactionId(playerFactionId);
            targetMarket.setPlayerOwned(true);

            targetMarket.setAdmin(Global.getSector().getPlayerPerson());
            makeProperPlayerColony(targetMarket);
            if (oldMarket != null) {
                targetMarket.setSize(oldMarket.getSize());

                for (Industry oldIndustry : new ArrayList<Industry>(oldMarket.getIndustries())) {
                    if (oldIndustry == null) continue;

                    String id = oldIndustry.getId();
                    if (id == null) continue;

                    if (!targetMarket.hasIndustry(id)) {
                        targetMarket.addIndustry(id);
                    }
                }

                if (oldMarket.hasSubmarket("storage") && !targetMarket.hasSubmarket("storage")) {
                    targetMarket.addSubmarket("storage");
                }

                SectorEntityToken oldEntity = oldMarket.getPrimaryEntity();
                if (oldEntity != null) {
                    StarSystemAPI oldSystem = oldEntity.getStarSystem();
                    Global.getSector().getEconomy().removeMarket(oldMarket);

                    if (oldEntity instanceof PlanetAPI) {
                        PlanetAPI oldPlanet = (PlanetAPI) oldEntity;
                        oldPlanet.getMemoryWithoutUpdate().unset("$surveyLevel");
                        oldPlanet.getMemoryWithoutUpdate().unset("$surveyData");
                        oldPlanet.getMemoryWithoutUpdate().unset("$surveyState");

                        MarketAPI husk = Global.getFactory().createMarket(
                                oldPlanet.getId() + "_husk",
                                oldPlanet.getName(),
                                0
                        );
                        husk.setFactionId(Factions.NEUTRAL);
                        husk.setSurveyLevel(MarketAPI.SurveyLevel.NONE);
                        husk.setPrimaryEntity(oldPlanet);
                        oldPlanet.setMarket(husk);
                        Global.getSector().getEconomy().addMarket(husk, true);
                    }

                    scrubSurveyData(oldSystem);
                }
            }
            if (!targetMarket.hasIndustry("population")) {
                targetMarket.addIndustry("population");
            }
            if (!targetMarket.hasIndustry("spaceport")) {
                targetMarket.addIndustry("spaceport");
            }

            if (!targetMarket.hasSubmarket(Submarkets.SUBMARKET_STORAGE)) {
                targetMarket.addSubmarket(Submarkets.SUBMARKET_STORAGE);
            }

            targetMarket.setSurveyLevel(MarketAPI.SurveyLevel.FULL);

            //targetMarket.reapplyConditions();
            targetMarket.reapplyIndustries();
        }

        private void makeProperPlayerColony(MarketAPI market) {
            String playerFactionId = Factions.PLAYER;

            market.setFactionId(playerFactionId);
            market.setPlayerOwned(true);
            market.setAdmin(Global.getSector().getPlayerPerson());

            // Make it behave like a real colony
            if (!market.hasIndustry(Industries.POPULATION)) {
                market.addIndustry(Industries.POPULATION);
            }
            if (!market.hasIndustry(Industries.SPACEPORT)) {
                market.addIndustry(Industries.SPACEPORT);
            }

            // Storage / cargo
            if (!market.hasSubmarket(Submarkets.SUBMARKET_STORAGE)) {
                market.addSubmarket(Submarkets.SUBMARKET_STORAGE);
            }

            // Local resources / stockpile
            if (!market.hasSubmarket("local_resources")) {
                market.addSubmarket("local_resources");
            }

            market.setSurveyLevel(MarketAPI.SurveyLevel.FULL);
            //market.reapplyConditions();
            market.reapplyIndustries();
        }
    }
}