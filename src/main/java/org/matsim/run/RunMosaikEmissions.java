package org.matsim.run;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.network.Link;
import org.matsim.application.MATSimApplication;
import org.matsim.contrib.emissions.EmissionUtils;
import org.matsim.contrib.emissions.PositionEmissionsModule;
import org.matsim.contrib.emissions.events.ColdEmissionEvent;
import org.matsim.contrib.emissions.events.WarmEmissionEvent;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.Config;
import org.matsim.core.config.groups.ControlerConfigGroup;
import org.matsim.core.config.groups.QSimConfigGroup;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.AfterMobsimEvent;
import org.matsim.core.controler.events.BeforeMobsimEvent;
import org.matsim.core.controler.events.ShutdownEvent;
import org.matsim.core.controler.listener.AfterMobsimListener;
import org.matsim.core.controler.listener.BeforeMobsimListener;
import org.matsim.core.controler.listener.ShutdownListener;
import org.matsim.core.events.EventsManagerImpl;
import org.matsim.core.events.algorithms.EventWriter;
import org.matsim.core.events.algorithms.EventWriterXML;
import org.matsim.core.events.handler.BasicEventHandler;
import org.matsim.core.utils.geometry.geotools.MGC;
import org.matsim.core.utils.gis.ShapeFileReader;
import org.matsim.vis.snapshotwriters.PositionEvent;
import org.matsim.vis.snapshotwriters.SnapshotWritersModule;
import org.opengis.feature.simple.SimpleFeature;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

public class RunMosaikEmissions extends RunOpenBerlinScenario {

    @CommandLine.Option(names = "--filter", description = "Path to filter", required = false)
    private Path snapshotFilter;

    public static void main(String[] args) {
        MATSimApplication.run(RunMosaikEmissions.class);
    }

    @Override
    protected Config prepareConfig(Config config) {

        config = super.prepareConfig(config);

        // tell the default event writer to not write any events
        config.controler().setWriteEventsInterval(0);
        config.controler().setFirstIteration(0);
        config.controler().setLastIteration(0);

        config.qsim().setSnapshotPeriod(1);
        config.qsim().setSnapshotStyle(QSimConfigGroup.SnapshotStyle.kinematicWaves);
        config.qsim().setLinkWidthForVis(0);
        config.controler().setWriteSnapshotsInterval(1);
        config.controler().setSnapshotFormat(Set.of(ControlerConfigGroup.SnapshotFormat.positionevents));
        // we want simstepparalleleventsmanagerimpl
        config.parallelEventHandling().setSynchronizeOnSimSteps(true);
        config.qsim().setFilterSnapshots(QSimConfigGroup.FilterSnapshots.withLinkAttributes);

        return config;
    }

    @Override
    protected void prepareScenario(Scenario scenario) {

        super.prepareScenario(scenario);

        scenario.getNetwork().getLinks().values().parallelStream()
                .filter(l -> l.getAllowedModes().contains(TransportMode.pt))
                .forEach(l -> EmissionUtils.setHbefaRoadType(l, ""));

        var bbox = loadStudyArea(this.snapshotFilter);
        scenario.getNetwork().getLinks().values().parallelStream()
                .filter(l -> isCoveredBy(l, bbox))
                .forEach(l -> l.getAttributes().putAttribute(SnapshotWritersModule.GENERATE_SNAPSHOT_FOR_LINK_KEY, true));
    }

    @Override
    protected void prepareControler(Controler controler) {

        controler.addOverridingModule(new PositionEmissionsModule());
        controler.addOverridingModule(new AbstractModule() {
            @Override
            public void install() {
                addControlerListenerBinding().to(WriterSetUp.class);
                bind(EventsManager.class).to(EventsManagerImpl.class).in(Singleton.class);
            }
        });
    }

    private static PreparedGeometry loadStudyArea(Path shapeFile) {

        var geometry = ShapeFileReader.getAllFeatures(shapeFile.toString()).stream()
                .map(SimpleFeature::getDefaultGeometry)
                .map(o -> (Geometry) o)
                .findFirst()
                .orElseThrow();
        var geometryFactory = new PreparedGeometryFactory();
        return geometryFactory.create(geometry);
    }

    private static boolean isCoveredBy(Link link, PreparedGeometry geometry) {
        return geometry.covers(MGC.coord2Point(link.getFromNode().getCoord()))
                || geometry.covers(MGC.coord2Point(link.getToNode().getCoord()));
    }

    private static class WriterSetUp implements BeforeMobsimListener, AfterMobsimListener, ShutdownListener {

        @Inject
        private OutputDirectoryHierarchy outputDirectoryHierarchy;

        @Inject
        private EventsManager eventsManager;

        @Inject
        private ControlerConfigGroup controlerConfig;

        private final Set<EventWriter> writers = new HashSet<>();

        @Override
        public void notifyBeforeMobsim(BeforeMobsimEvent event) {

            if (event.getIteration() != controlerConfig.getLastIteration()) return;


            var eventsFile = outputDirectoryHierarchy.getIterationFilename(event.getIteration(), "events.xml.gz");
            var positionEmissionEventsFile = outputDirectoryHierarchy.getIterationFilename(event.getIteration(), "position-emission-events.xml.gz");
            var emissionEventsFile = outputDirectoryHierarchy.getIterationFilename(event.getIteration(), "emission-events.xml.gz");

            // write everything except: positions, position-emissions, warm-emissions, cold-emissions
            var normalWriter = new FilterEventsWriter(
                    e -> (
                            !e.getEventType().equals(PositionEvent.EVENT_TYPE)
                                    && !e.getEventType().equals(PositionEmissionsModule.PositionEmissionEvent.EVENT_TYPE)
                                    && !e.getEventType().equals(WarmEmissionEvent.EVENT_TYPE)
                                    && !e.getEventType().equals(ColdEmissionEvent.EVENT_TYPE)

                    ), eventsFile);

            // write only position-emissions
            var positionEmissionWriter = new FilterEventsWriter(e -> e.getEventType().equals(PositionEmissionsModule.PositionEmissionEvent.EVENT_TYPE), emissionEventsFile);
            var emissionWriter = new FilterEventsWriter(e -> e.getEventType().equals(WarmEmissionEvent.EVENT_TYPE) || e.getEventType().equals(ColdEmissionEvent.EVENT_TYPE), emissionEventsFile);

            eventsManager.addHandler(normalWriter);
            eventsManager.addHandler(emissionWriter);
            eventsManager.addHandler(positionEmissionWriter);
            writers.add(normalWriter);
            writers.add(emissionWriter);
            writers.add(positionEmissionWriter);
        }

        @Override
        public void notifyAfterMobsim(AfterMobsimEvent event) {
            closeWriters();
        }

        @Override
        public void notifyShutdown(ShutdownEvent event) {
            if (event.isUnexpected()) {
                closeWriters();
            }
        }

        private void closeWriters() {
            for (EventWriter writer : writers) {
                writer.closeFile();
            }
        }
    }

    private static class FilterEventsWriter implements BasicEventHandler, EventWriter {

        private final Predicate<Event> filter;
        private final EventWriterXML writer;
        private final String filename;

        private final AtomicInteger counter = new AtomicInteger();

        public FilterEventsWriter(Predicate<Event> filter, String outFilename) {
            this.filter = filter;
            this.writer = new EventWriterXML(outFilename);
            this.filename = outFilename;
        }

        @Override
        public void closeFile() {
            writer.closeFile();
        }

        @Override
        public void handleEvent(Event event) {

            if (filter.test(event)) {
                if (counter.incrementAndGet() % 10000 == 0) {
                    System.out.println(filename + ": " + counter);
                }
                writer.handleEvent(event);
            }
        }
    }
}
