package AOTDVokPlugin;

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
import com.fs.starfarer.api.impl.campaign.ids.*;
import com.fs.starfarer.api.util.IntervalUtil;
import lunalib.lunaSettings.LunaSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class AOTDVokPlugin extends BaseModPlugin {

    private static final String NIDAVELLIR = "aotd_nidavelir_complex";
    private static final String PLUTO = "aotd_pluto_station";
    StarSystemAPI chosen = null;

    @Override
    public void onGameLoad(boolean newGame) {
        if (!LunaSettings.getBoolean("megastructure_patch", "disableMod")) {
            if (newGame) {
                relocateMegas();
                if (LunaSettings.getBoolean("megastructure_patch", "forceStart")) {
                    Global.getSector().addTransientScript(new OwnFactionRelocationScript(chosen.getId()));
                }
            }
        }
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
                    PlanetAPI oldPlanet = playerMarket.getPlanetEntity();
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
            List<PlanetAPI> eligible = new ArrayList<>();
            List<PlanetAPI> terran = new ArrayList<>();
            List<PlanetAPI> terranEccentric = new ArrayList<>();
            List<PlanetAPI> jungle = new ArrayList<>();
            List<PlanetAPI> tundraOrArid = new ArrayList<>();
            List<PlanetAPI> desert = new ArrayList<>();

            for (PlanetAPI planet : system.getPlanets()) {
                if (planet == null) continue;
                if (planet.isStar() || planet.isGasGiant()) continue;
                if (hasForbiddenAotdCondition(planet)) continue;

                eligible.add(planet);

                String typeId = planet.getTypeId();
                if ("terran".equals(typeId)) {
                    terran.add(planet);
                } else if ("terran-eccentric".equals(typeId)) {
                    terranEccentric.add(planet);
                } else if ("jungle".equals(typeId)) {
                    jungle.add(planet);
                } else if ("tundra".equals(typeId) || "arid".equals(typeId)) {
                    tundraOrArid.add(planet);
                } else if ("desert".equals(typeId)) {
                    desert.add(planet);
                }
            }

            Random random = new Random();

            if (!terran.isEmpty()) return terran.get(random.nextInt(terran.size()));
            if (!terranEccentric.isEmpty()) return terranEccentric.get(random.nextInt(terranEccentric.size()));
            if (!jungle.isEmpty()) return jungle.get(random.nextInt(jungle.size()));
            if (!tundraOrArid.isEmpty()) return tundraOrArid.get(random.nextInt(tundraOrArid.size()));
            if (!desert.isEmpty()) return desert.get(random.nextInt(desert.size()));

            if (!eligible.isEmpty()) {
                return eligible.get(random.nextInt(eligible.size()));
            }
            else {
                return system.getPlanets().get(random.nextInt(system.getPlanets().size()));
            }
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

    private void relocateMegas() {

        StarSystemAPI targetSystem = findBlueGiantHypershuntSystem();

        if (targetSystem == null) {
            Global.getLogger(this.getClass()).warn(
                    "No Blue Giant + Hypershunt system found.");
            return;
        }

        PlanetAPI oldNidPlanet = null;
        PlanetAPI oldPlutoPlanet = null;

        for (StarSystemAPI system : Global.getSector().getStarSystems()) {
            for (PlanetAPI planet : system.getPlanets()) {

                if (planet.getMarket() == null) continue;

                if (planet.getMarket().hasCondition(NIDAVELLIR)) {
                    oldNidPlanet = planet;
                }

                if (planet.getMarket().hasCondition(PLUTO)) {
                    oldPlutoPlanet = planet;
                }
            }
        }

        if (oldNidPlanet == null || oldPlutoPlanet == null) {
            Global.getLogger(this.getClass()).warn(
                    "Could not locate existing VoK megastructures.");
            return;
        }

        oldNidPlanet.getMarket().removeCondition(NIDAVELLIR);
        oldPlutoPlanet.getMarket().removeCondition(PLUTO);

        PlanetAPI plutoTarget = findPlanet(targetSystem);
        addPluto(plutoTarget);

        PlanetAPI nidTarget = findPlanet(targetSystem);
        addNidavellir(nidTarget);

        Global.getLogger(this.getClass()).info(
                "Moved Pluto and Nidavellir to "
                        + targetSystem.getName());
        chosen = targetSystem;
    }

    private StarSystemAPI findBlueGiantHypershuntSystem() {

        for (StarSystemAPI system : Global.getSector().getStarSystems()) {

            if (system.getStar() == null) continue;

            boolean blueGiant = system.getStar().getTypeId().equals(StarTypes.BLUE_GIANT);

            if (!blueGiant) continue;

            boolean hypershunt = false;

            for (SectorEntityToken entity : system.getAllEntities()) {

                if (entity.hasTag(Tags.CORONAL_TAP)) {
                    hypershunt = true;
                    break;
                }

                String id = entity.getCustomEntityType();

                if ("coronal_tap".equals(id)) {
                    hypershunt = true;
                    break;
                }
            }

            if (hypershunt) {
                return system;
            }
        }

        return null;
    }

    private PlanetAPI findPlanet(StarSystemAPI system) {

        List<PlanetAPI> candidates = new ArrayList<>();

        for (PlanetAPI planet : system.getPlanets()) {

            if (planet.isStar()) continue;
            if (planet.isMoon()) continue;
            if (planet.isGasGiant()) continue;
            if (planet.hasCondition(PLUTO)) continue;
            if (planet.hasCondition(NIDAVELLIR)) continue;

            candidates.add(planet);
        }

        if (candidates.isEmpty()) {
            return null;
        }

        Random rand = new Random();
        return candidates.get(rand.nextInt(candidates.size()));
    }

    private void addNidavellir(PlanetAPI planet) {

        if (planet == null || planet.getMarket() == null) {
            return;
        }

        String token =
                planet.getMarket().addCondition(NIDAVELLIR);

        planet.getMarket()
                .getSpecificCondition(token)
                .setSurveyed(false);

        planet.getMarket().removeCondition(
                Conditions.RUINS_SCATTERED);
        planet.getMarket().removeCondition(
                Conditions.RUINS_WIDESPREAD);
        planet.getMarket().removeCondition(
                Conditions.RUINS_EXTENSIVE);

        if (!planet.getMarket().hasCondition(
                Conditions.RUINS_VAST)) {

            planet.getMarket().addCondition(
                    Conditions.RUINS_VAST);
        }
    }

    private void addPluto(PlanetAPI planet) {

        if (planet == null || planet.getMarket() == null) {
            return;
        }

        String token =
                planet.getMarket().addCondition(PLUTO);

        planet.getMarket()
                .getSpecificCondition(token)
                .setSurveyed(false);
    }
}