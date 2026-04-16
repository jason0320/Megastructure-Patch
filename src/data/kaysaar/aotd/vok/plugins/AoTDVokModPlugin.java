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
import com.fs.starfarer.api.util.IntervalUtil;
import data.kaysaar.aotd.vok.campaign.econ.globalproduction.models.GPManager;
import data.kaysaar.aotd.vok.misc.AoTDMisc;
import org.apache.log4j.Logger;

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
        private final IntervalUtil interval = new IntervalUtil(0.1f, 0.2f);
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
        private static final Logger log = Global.getLogger(AoTDVokModPlugin.class);

        private void relocateColony(MarketAPI oldMarket, PlanetAPI targetPlanet) {
            // 1. Capture the original conditions from the planet's "wild" market
            MarketAPI wildMarket = targetPlanet.getMarket();
            List<String> conditionIdsToCopy = new ArrayList<>();
            if (wildMarket != null) {
                for (MarketConditionAPI cond : wildMarket.getConditions()) {
                    conditionIdsToCopy.add(cond.getId());
                }
            }

            // 2. Create the new Player Market
            MarketAPI targetMarket = Global.getFactory().createMarket(
                    targetPlanet.getId() + "_aotd_colony",
                    targetPlanet.getName(),
                    oldMarket != null ? oldMarket.getSize() : 3
            );

            // 3. CRITICAL: Set Faction and Survey Level BEFORE registration
            targetMarket.setFactionId(Factions.PLAYER);
            targetMarket.setPlayerOwned(true);
            targetMarket.setSurveyLevel(MarketAPI.SurveyLevel.FULL);

            // Set memory flags for UI/Scripting consistency
            targetMarket.getMemoryWithoutUpdate().set("$surveyLevel", MarketAPI.SurveyLevel.FULL);
            targetPlanet.getMemoryWithoutUpdate().set("$surveyLevel", MarketAPI.SurveyLevel.FULL);

            // 4. Link Planet and Market
            targetMarket.setPrimaryEntity(targetPlanet);
            targetPlanet.setMarket(targetMarket);
            targetPlanet.setFaction(Factions.PLAYER);

            // 5. Register with Economy (This "wakes up" the market)
            Global.getSector().getEconomy().addMarket(targetMarket, true);

            // 6. Add Conditions and FORCE them to be surveyed/visible
            for (String id : conditionIdsToCopy) {
                if (!targetMarket.hasCondition(id)) {
                    targetMarket.addCondition(id);
                    // This ensures the Ores/Rare Ores actually show up in the UI
                    MarketConditionAPI newlyAdded = targetMarket.getCondition(id);
                    if (newlyAdded != null) {
                        newlyAdded.setSurveyed(true);
                    }
                }
            }

            // 7. Industry Copy Loop (with Farming/Aquaculture logic)
            if (oldMarket != null) {
                targetMarket.setSize(oldMarket.getSize());

                // Check for specific resource requirements
                boolean hasFarmland = false;
                for (String id : conditionIdsToCopy) {
                    if (id.startsWith("farmland_")) { hasFarmland = true; break; }
                }
                boolean hasWaterSurface = conditionIdsToCopy.contains(Conditions.WATER_SURFACE);

                for (Industry oldInd : new ArrayList<>(oldMarket.getIndustries())) {
                    String id = oldInd.getId();
                    if (id.equals(Industries.FARMING) && !hasFarmland) continue;
                    if (id.equals(Industries.AQUACULTURE) && !hasWaterSurface) continue;

                    if (!targetMarket.hasIndustry(id)) {
                        targetMarket.addIndustry(id);
                    }
                }

                // Submarkets
                if (oldMarket.hasSubmarket(Submarkets.SUBMARKET_STORAGE)) {
                    if (!targetMarket.hasSubmarket(Submarkets.SUBMARKET_STORAGE)) {
                        targetMarket.addSubmarket(Submarkets.SUBMARKET_STORAGE);
                    }
                }
            }

            // 8. Ensure Base Essentials
            if (!targetMarket.hasIndustry(Industries.POPULATION)) targetMarket.addIndustry(Industries.POPULATION);
            if (!targetMarket.hasIndustry(Industries.SPACEPORT)) targetMarket.addIndustry(Industries.SPACEPORT);
            if (!targetMarket.hasSubmarket("local_resources")) targetMarket.addSubmarket("local_resources");

            targetMarket.setAdmin(Global.getSector().getPlayerPerson());

            // 9. Clean up the old location
            if (oldMarket != null && oldMarket.getPrimaryEntity() != null) {
                SectorEntityToken oldEntity = oldMarket.getPrimaryEntity();
                Global.getSector().getEconomy().removeMarket(oldMarket);

                if (oldEntity instanceof PlanetAPI) {
                    PlanetAPI oldPlanet = (PlanetAPI) oldEntity;
                    // Create the neutral husk
                    MarketAPI husk = Global.getFactory().createMarket(oldPlanet.getId() + "_husk", oldPlanet.getName(), 0);
                    husk.setFactionId(Factions.NEUTRAL);
                    husk.setPrimaryEntity(oldPlanet);
                    oldPlanet.setMarket(husk);
                    Global.getSector().getEconomy().addMarket(husk, true);
                }
            }

            // 10. Final Refresh
            targetMarket.reapplyConditions();
            targetMarket.reapplyIndustries();
        }
    }
}