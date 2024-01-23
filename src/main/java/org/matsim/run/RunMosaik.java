package org.matsim.run;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.application.MATSimApplication;
import org.matsim.core.config.Config;
import org.matsim.core.config.groups.StrategyConfigGroup;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.replanning.strategies.DefaultPlanStrategiesModule;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.facilities.ActivityFacilitiesFactoryImpl;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class RunMosaik extends RunOpenBerlinScenario {

	private static final Logger log = LogManager.getLogger(RunMosaik.class);

	public static void main(String[] args) {
		MATSimApplication.run(RunMosaik.class, args);
	}

	@Override
	protected Config prepareConfig(Config config) {

		log.info("!!!!!!!!!!!");
		log.info("You are running the mosaik runner. It will reset strategy settings, to only allow re-routing");
		log.info("!!!!!!!!!!!");

		config = super.prepareConfig(config);
		config.controler().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);

		// clear all strategies and only add reRoute for all subpopulations
		config.strategy().clearStrategySettings();

		for (String subpopulation : List.of("person", "freight", "goodsTraffic", "commercialPersonTraffic", "commercialPersonTraffic_service")) {
			var reRoute = new StrategyConfigGroup.StrategySettings()
				.setStrategyName(DefaultPlanStrategiesModule.DefaultStrategy.ReRoute)
				.setWeight(0.5)
				.setSubpopulation(subpopulation);
			config.strategy().addStrategySettings(reRoute);

			var changeExpBeta = new StrategyConfigGroup.StrategySettings()
				.setStrategyName(DefaultPlanStrategiesModule.DefaultSelector.ChangeExpBeta)
				.setWeight(0.5)
				.setSubpopulation(subpopulation);
			config.strategy().addStrategySettings(changeExpBeta);
		}
		config.strategy().setFractionOfIterationsToDisableInnovation(0.8);
		return config;
	}

	@Override
	protected void prepareScenario(Scenario scenario) {

		super.prepareScenario(scenario);

		log.info("Removing references to old network, because we have switched the network.");

		// remove linkids from activities
		scenario.getPopulation().getPersons().values().parallelStream()
			.flatMap(person -> person.getPlans().stream())
			.flatMap(plan -> plan.getPlanElements().stream())
			.filter(element -> element instanceof Activity)
			.map(element -> (Activity) element)
			.forEach(activity -> activity.setLinkId(null));

		// replace trips in plans with single empty legs which only have a main mode
		for (var person : scenario.getPopulation().getPersons().values()) {

			// copy plans
			List<Plan> plans = new ArrayList<>(person.getPlans());

			// remove old reference
			person.getPlans().clear();
			person.setSelectedPlan(null);

			for (var plan : plans) {

				var trips = TripStructureUtils.getTrips(plan);
				var newPlan = scenario.getPopulation().getFactory().createPlan();
				newPlan.addActivity(PopulationUtils.getFirstActivity(plan));

				for (var trip : trips) {

					var mainMode = TripStructureUtils.getRoutingModeIdentifier().identifyMainMode(trip.getTripElements());
					var leg = scenario.getPopulation().getFactory().createLeg(mainMode);
					newPlan.addLeg(leg);
					newPlan.addActivity(trip.getDestinationActivity());
				}

				person.addPlan(newPlan);
			}
		}

		// remove linkids from facilities
		// facilities are basically immutable. Create new facilities without link references
		var factory = new ActivityFacilitiesFactoryImpl();
		var facilities = scenario.getActivityFacilities().getFacilities().values().parallelStream()
			.map(facility -> factory.createActivityFacility(facility.getId(), facility.getCoord()))
			.collect(Collectors.toSet());

		// throw out old facilities
		scenario.getActivityFacilities().getFacilities().clear();

		// add new facilities to scenario
		for (var facility : facilities) {
			scenario.getActivityFacilities().addActivityFacility(facility);
		}
	}
}
