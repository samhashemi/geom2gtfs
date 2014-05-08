package com.conveyal.geom2gtfs;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.geotools.data.DataStore;
import org.geotools.data.DataStoreFinder;
import org.geotools.data.FeatureSource;
import org.geotools.feature.FeatureCollection;
import org.geotools.feature.FeatureIterator;
import org.onebusaway.gtfs.model.Agency;
import org.onebusaway.gtfs.model.AgencyAndId;
import org.onebusaway.gtfs.model.Frequency;
import org.onebusaway.gtfs.model.Route;
import org.onebusaway.gtfs.model.ServiceCalendar;
import org.onebusaway.gtfs.model.Stop;
import org.onebusaway.gtfs.model.StopTime;
import org.onebusaway.gtfs.model.Trip;
import org.onebusaway.gtfs.model.calendar.ServiceDate;
import org.onebusaway.gtfs.serialization.GtfsWriter;
import org.opengis.feature.Feature;
import org.opengis.feature.GeometryAttribute;

import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.LineString;
import com.vividsolutions.jts.geom.MultiLineString;

public class Main {

	private static final String DEFAULT_AGENCY_ID = "0";
	private static final String DEFAULT_CAL_ID = "0";
	private static final boolean FAIL_ON_MULTILINESTRING = true;
	static Config config;

	static GtfsQueue queue = null;

	public static void main(String[] args) throws Exception {
		if (args.length < 3) {
			System.out.println("usage: cmd shapefile_fn config_fn output_fn");
			return;
		}

		String fn = args[0];
		String config_fn = args[1];
		String output_fn = args[2];

		config = new Config(config_fn);

		// check if config specifies a csv join
		CsvJoinTable csvJoin = config.getCsvJoin();

		queue = new GtfsQueue();

		ServiceCalendar cal = new ServiceCalendar();
		cal.setMonday(1);
		cal.setTuesday(1);
		cal.setWednesday(1);
		cal.setThursday(1);
		cal.setFriday(1);
		cal.setSaturday(1);
		cal.setSunday(1);
		cal.setStartDate(new ServiceDate(config.getStartDate()));
		cal.setEndDate(new ServiceDate(config.getEndDate()));
		cal.setServiceId(new AgencyAndId(DEFAULT_AGENCY_ID, DEFAULT_CAL_ID));
		queue.calendars.add(cal);

		Agency agency = new Agency();
		agency.setId(DEFAULT_AGENCY_ID);
		agency.setName(config.getAgencyName());
		agency.setUrl(config.getAgencyUrl());
		agency.setTimezone(config.getAgencyTimezone());
		queue.agencies.add(agency);

		FeatureSource<?, ?> lines = getFeatureSource(fn);

		FeatureCollection<?, ?> featCol = lines.getFeatures();
		FeatureIterator<?> features = featCol.features();
		while (features.hasNext()) {
			Feature feat = (Feature) features.next();

			ExtendedFeature exft = new ExtendedFeature(feat, csvJoin);

			if (!config.passesFilter(exft)) {
				continue;
			}

			System.out.println("generating stops for \"" + feat.getProperty(config.getRouteNamePropName()).getValue()
					+ "\"");

			featToGtfs(exft, agency);
		}

		GtfsWriter gtfsWriter = new GtfsWriter();
		gtfsWriter.setOutputLocation(new File(output_fn));
		queue.dumpToWriter(gtfsWriter);
		gtfsWriter.close();
	}

	private static void featToGtfs(ExtendedFeature exft, Agency agency) throws Exception {

		GeometryAttribute geomAttr = exft.feat.getDefaultGeometryProperty();
		MultiLineString geom = (MultiLineString) geomAttr.getValue();

		// get route type
		Integer mode = config.getMode(exft.feat);

		// figure out spacing and speed for mode
		Integer spacing = config.getSpacing(exft.feat);
		Double speed = config.getSpeed(exft.feat);

		// generate route
		String routeId = exft.getProperty(config.getRouteIdPropName());
		String routeName = exft.getProperty(config.getRouteNamePropName());
		Route route = new Route();
		route.setId(new AgencyAndId(DEFAULT_AGENCY_ID, routeId));
		route.setShortName(routeName);
		route.setAgency(agency);
		route.setType(mode);
		queue.routes.add(route);

		// generate stops
		List<List<ProtoRouteStop>> prsss = makeProtoRouteStops(geom, spacing, routeId);

		if (FAIL_ON_MULTILINESTRING && prsss.size() > 1) {
			throw new Exception("Features may only contain a single linestring.");
		}

		for (List<ProtoRouteStop> prss : prsss) {
			Map<ProtoRouteStop, Stop> prsStops = new HashMap<ProtoRouteStop, Stop>();
			for (ProtoRouteStop prs : prss) {
				// generate stops
				Stop stop = new Stop();
				stop.setLat(prs.coord.y);
				stop.setLon(prs.coord.x);
				stop.setId(new AgencyAndId(DEFAULT_AGENCY_ID, String.valueOf(queue.stops.size())));
				stop.setName(String.valueOf(queue.stops.size()));
				queue.stops.add(stop);

				prsStops.put(prs, stop);
			}

			makeFrequencyTrip(exft, prss, route, prsStops, false, speed, config.usePeriods(), config.waitFactor());
			if (config.isBidirectional()) {
				makeFrequencyTrip(exft, prss, route, prsStops, true, speed, config.usePeriods(), config.waitFactor());
			}
		}

	}

	private static void makeFrequencyTrip(ExtendedFeature exft, List<ProtoRouteStop> prss, Route route,
			Map<ProtoRouteStop, Stop> prsStops, boolean reverse, double speed, boolean usePeriods, double waitFactor) {
		// generate a trip
		Trip trip = new Trip();
		trip.setRoute(route);
		trip.setId(new AgencyAndId(DEFAULT_AGENCY_ID, String.valueOf(queue.trips.size())));
		trip.setServiceId(new AgencyAndId(DEFAULT_AGENCY_ID, DEFAULT_CAL_ID));
		queue.trips.add(trip);

		// generate a frequency
		for (ServiceWindow window : config.getServiceWindows()) {
			Frequency freq = makeFreq(exft, window.start, window.end, window.propName, trip, usePeriods, waitFactor);
			if (freq != null) {
				queue.frequencies.add(freq);
			}
		}

		double firstStopDist = 0;
		for (int i = 0; i < prss.size(); i++) {

			int ix = i;
			if (reverse) {
				ix = prss.size() - 1 - i;
			}
			ProtoRouteStop prs = prss.get(ix);
			if (i == 0) {
				firstStopDist = prs.dist;
			}
			Stop stop = prsStops.get(prs);

			// generate stoptime
			StopTime stoptime = new StopTime();
			stoptime.setStop(stop);
			stoptime.setTrip(trip);
			stoptime.setStopSequence(i);
			double dist = Math.abs(prs.dist - firstStopDist);
			int time = (int) (dist / speed);
			stoptime.setArrivalTime(time);
			stoptime.setDepartureTime(time);
			queue.stoptimes.add(stoptime);

		}
	}

	private static Frequency makeFreq(ExtendedFeature exft, int beginHour, int endHour, String propName, Trip trip,
			boolean usePeriods, double waitFactor) {
		Frequency freq;
		double headway;

		freq = new Frequency();
		freq.setStartTime(beginHour * 3600);
		freq.setEndTime(endHour * 3600);
		String freqStr = exft.getProperty(propName);
		if (freqStr == null || freqStr.equals("None")) {
			return null;
		}
		Double freqDbl = Double.parseDouble(freqStr);
		if (freqDbl == 0.0) {
			return null;
		}
		if (usePeriods) {
			headway = Double.parseDouble(freqStr) * 60; // minutes to seconds
		} else {
			double perHour = Double.parseDouble(freqStr); // arrivals per hour
			headway = 3600 / perHour;
		}

		freq.setHeadwaySecs((int) (headway / waitFactor));

		freq.setTrip(trip);

		return freq;
	}

	private static List<List<ProtoRouteStop>> makeProtoRouteStops(MultiLineString geom, double spacing, String routeId) {
		List<List<ProtoRouteStop>> ret = new ArrayList<List<ProtoRouteStop>>();

		for (int i = 0; i < geom.getNumGeometries(); i++) {
			LineString ls = (LineString) geom.getGeometryN(i);
			List<ProtoRouteStop> substr = makeProtoRouteStopsFromLinestring(ls, spacing, routeId);
			ret.add(substr);
		}

		return ret;
	}

	private static List<ProtoRouteStop> makeProtoRouteStopsFromLinestring(LineString geom, double spacing,
			String routeId) {
		List<ProtoRouteStop> ret = new ArrayList<ProtoRouteStop>();

		Coordinate[] coords = geom.getCoordinates();
		double overshot = 0;
		double segStartDist = 0;

		double totalLen = 0;
		for (int i = 0; i < coords.length - 1; i++) {
			Coordinate p1 = coords[i];
			Coordinate p2 = coords[i + 1];

			double segCurs = overshot;
			double segLen = GeoMath.greatCircle(p1, p2);
			totalLen += segLen;

			while (segCurs < segLen) {
				double index = segCurs / segLen;
				Coordinate interp = GeoMath.interpolate(p1, p2, index);

				ProtoRouteStop prs = new ProtoRouteStop();
				prs.coord = interp;
				prs.routeId = routeId;
				prs.dist = segStartDist + segCurs;
				ret.add(prs);

				segCurs += spacing;
			}

			overshot = segCurs - segLen;

			segStartDist += segLen;
		}

		// add one final stop, at the very end
		ProtoRouteStop prs = new ProtoRouteStop();
		prs.coord = coords[coords.length - 1];
		prs.routeId = routeId;
		prs.dist = totalLen;
		ret.add(prs);

		return ret;
	}

	private static FeatureSource<?, ?> getFeatureSource(String shp_filename) throws MalformedURLException, IOException {
		// construct shapefile factory
		File file = new File(shp_filename);
		Map<String, URL> map = new HashMap<String, URL>();
		map.put("url", file.toURI().toURL());
		DataStore dataStore = DataStoreFinder.getDataStore(map);

		// get shapefile as generic 'feature source'
		String typeName = dataStore.getTypeNames()[0];
		FeatureSource<?, ?> source = dataStore.getFeatureSource(typeName);
		return source;
	}

}