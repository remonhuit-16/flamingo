/*
 * Copyright (C) 2017 B3Partners B.V.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package nl.b3p.viewer.stripes;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.linearref.LengthIndexedLine;
import org.locationtech.jts.simplify.TopologyPreservingSimplifier;
import org.locationtech.jts.util.GeometricShapeFactory;
import java.awt.geom.AffineTransform;
import java.io.StringReader;
import java.util.Iterator;
import net.sourceforge.stripes.action.ActionBean;
import net.sourceforge.stripes.action.ActionBeanContext;
import net.sourceforge.stripes.action.DefaultHandler;
import net.sourceforge.stripes.action.Resolution;
import net.sourceforge.stripes.action.StreamingResolution;
import net.sourceforge.stripes.action.StrictBinding;
import net.sourceforge.stripes.action.UrlBinding;
import net.sourceforge.stripes.validation.Validate;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.geotools.geometry.jts.JTS;
import org.geotools.geometry.jts.WKTReader2;
import org.geotools.referencing.operation.transform.AffineTransform2D;
import org.json.JSONArray;
import org.json.JSONObject;
import org.opengis.referencing.operation.MathTransform;
import org.opengis.referencing.operation.TransformException;
import org.opensphere.geometry.algorithm.ConcaveHull;

/**
 *
 * @author Meine Toonen
 */
@UrlBinding("/action/ontbrandings")
@StrictBinding
public class OntbrandingsActionBean implements ActionBean {

    private static final Log LOG = LogFactory.getLog(OntbrandingsActionBean.class);

    private ActionBeanContext context;
    private WKTReader2 wkt;
    private GeometryFactory gf;

    @Validate
    private String features;
    

    @DefaultHandler
    public Resolution calculate() throws TransformException {
        JSONObject result = new JSONObject();
        result.put("type", "calculate");
        gf = new GeometryFactory(new PrecisionModel(), 28992);
        wkt = new WKTReader2(gf);

        JSONArray jsonFeatures = new JSONArray(features);
        JSONObject mainLocation = null;
        for (Iterator<Object> iterator = jsonFeatures.iterator(); iterator.hasNext();) {
            JSONObject feature = (JSONObject) iterator.next();
            JSONObject attrs = feature.getJSONObject("attributes");
            if (attrs.getString("type").equals("audienceLocation") && attrs.getBoolean("mainLocation")) {
                mainLocation = feature;
                break;
            }

        }
        JSONArray safetyZones = new JSONArray();
        for (Iterator<Object> iterator = jsonFeatures.iterator(); iterator.hasNext();) {
            JSONObject feature = (JSONObject) iterator.next();
            JSONObject safetyZone;
            try {
                JSONArray obs = calculateSafetyZone(feature, mainLocation);
                if (obs != null && obs.length() > 0) {
                    for (Iterator<Object> iterator1 = obs.iterator(); iterator1.hasNext();) {
                        JSONObject g = (JSONObject) iterator1.next();
                        safetyZones.put(g);
                    }
                }
            } catch (ParseException ex) {
                LOG.debug("Error calculating safetyzone: ", ex);
            }
        }

        result.put("safetyZones", safetyZones);
        return new StreamingResolution("application/json", new StringReader(result.toString()));
    }

    private JSONArray calculateSafetyZone(JSONObject feature, JSONObject mainLocation) throws ParseException, TransformException {
        JSONObject attributes = feature.getJSONObject("attributes");
        String type = attributes.getString("type");
        JSONArray gs = new JSONArray();
        if (type.equals("ignitionLocation")) {
            boolean fan = false;
            if (attributes.getString("fireworks_type").equals("consumer")) {
                fan = attributes.getBoolean("zonedistance_consumer_fan");
            } else {
                fan = attributes.getBoolean("zonedistance_professional_fan");
            }

            if (fan) {
                calculateFan(feature, mainLocation, gs);
            } else {
                calculateNormalSafetyZone(feature, mainLocation,gs);
            }
        }
        return gs;
    }

    private void calculateFan(JSONObject feature, JSONObject mainLocation, JSONArray gs) throws ParseException, TransformException {
        // Bereken centroide van feature: [1]
        // bereken centroide van hoofdlocatie: [2]
        // bereken lijn tussen de twee [1] en [2] centroides: [3]
        // bereken loodlijn op [3]: [4]
        // maak buffer in richting van [4] voor de fanafstand
        //Mogelijke verbetering, nu niet doen :// Voor elk vertex in feature, buffer met fan afstand in beiden richtingen van [4]
        // union alle buffers

        JSONObject attributes = feature.getJSONObject("attributes");
        double fanLength;
        double fanHeight;

        if (attributes.getString("fireworks_type").equals("consumer")) {
            fanLength = attributes.getDouble("zonedistance_consumer_m") * 1.5;
            fanHeight = attributes.getDouble("zonedistance_consumer_m");
        } else {
            fanLength = attributes.getDouble("zonedistance_professional_m") * 1.5;
            fanHeight = attributes.getDouble("zonedistance_professional_m");
        }
        Geometry ignition = wkt.read(feature.getString("wktgeom"));
        Geometry audience = wkt.read(mainLocation.getString("wktgeom"));
        Geometry boundary = ignition.getBoundary();
        LineString boundaryLS = (LineString)boundary;
        LengthIndexedLine lil = new LengthIndexedLine(boundaryLS);
        
        Point ignitionCentroid = ignition.getCentroid();
        Point audienceCentroid = audience.getCentroid();
        
        double offset = fanHeight / 2;
        int endIndex = (int)lil.getEndIndex();
        
        
        Geometry zone = createNormalSafetyZone(feature, ignition);
        Geometry unioned = zone;
        
        
        double dx = ignitionCentroid.getX() - audienceCentroid.getX();
        double dy = ignitionCentroid.getY() - audienceCentroid.getY();
   
        // if (showIntermediateResults) {

        /* double length = ls.getLength();
            double ratioX = (dx / length);
            double ratioY = (dy / length);
            double fanX = ratioX * fanLength;
            double fanY = ratioY * fanLength;
            Point eindLoodlijn = gf.createPoint(new Coordinate(ignitionCentroid.getX() + fanX, ignitionCentroid.getY() + fanY));
            Coordinate ancorPoint = ignitionCentroid.getCoordinate();

            double angleRad = Math.toRadians(90);
            AffineTransform affineTransform = AffineTransform.getRotateInstance(angleRad, ancorPoint.x, ancorPoint.y);
            MathTransform mathTransform = new AffineTransform2D(affineTransform);

            Geometry rotatedPoint = JTS.transform(eindLoodlijn, mathTransform);
            Coordinate[] loodLijnCoords = {ignitionCentroid.getCoordinate(), rotatedPoint.getCoordinate()};
            LineString loodLijn = gf.createLineString(loodLijnCoords);*/
 /*  gs.put(createFeature(eindLoodlijn, "temp", "eindLoodlijn"));
            gs.put(createFeature(loodLijn, "temp", "loodLijn"));
            gs.put(createFeature(rotatedPoint, "temp", "loodLijn2"));*/
        //}
        
        double theta = Math.atan2(dy, dx);
        double correctBearing = (Math.PI / 2);
        double rotation = theta - correctBearing;
        for (int i = 0; i < endIndex; i += offset) {
            Coordinate c = lil.extractPoint(i);
            Geometry fan = createEllipse(c, rotation, fanLength, fanHeight, 220);

            if (!fan.isEmpty()) {            
                unioned = unioned.union(fan);
            }
        }

        ConcaveHull con = new ConcaveHull(unioned, fanHeight);
        Geometry g = con.getConcaveHull();
        TopologyPreservingSimplifier tp = new TopologyPreservingSimplifier(g);
        tp.setDistanceTolerance(0.5);
        gs.put(createFeature(tp.getResultGeometry(), "safetyZone", ""));

        createSafetyDistances(gs, audience, ignition, g);
    }

    private void createSafetyDistances(JSONArray gs, Geometry audience, Geometry ignition, Geometry safetyZone) throws TransformException {
        // Create safetydistances
        // 1. afstand tussen rand afsteekzone en safetyzone: loodrecht op publiek
        Point audienceCentroid = audience.getCentroid();
        Point ignitionCentroid = ignition.getCentroid();
        Coordinate[] coords = {audienceCentroid.getCoordinate(), ignitionCentroid.getCoordinate()};
        LineString audience2ignition = gf.createLineString(coords);
        
        double dx = ignitionCentroid.getX() - audienceCentroid.getX();
        double dy = ignitionCentroid.getY() - audienceCentroid.getY();
        double length = audience2ignition.getLength();
        double ratioX = (dx / length);
        double ratioY = (dy / length);
        double fanX = ratioX * 1000;
        double fanY = ratioY * 1000;

        Point eindLoodlijn = gf.createPoint(new Coordinate(ignitionCentroid.getX() + fanX, ignitionCentroid.getY() + fanY));
        Coordinate ancorPoint = ignitionCentroid.getCoordinate();
        
        double angleRad = Math.toRadians(90);
        AffineTransform affineTransform = AffineTransform.getRotateInstance(angleRad, ancorPoint.x, ancorPoint.y);
        MathTransform mathTransform = new AffineTransform2D(affineTransform);

        Geometry rotatedPoint = JTS.transform(eindLoodlijn, mathTransform);
        Coordinate[] loodLijnCoords = {ignitionCentroid.getCoordinate(), rotatedPoint.getCoordinate()};
        LineString loodLijn = gf.createLineString(loodLijnCoords);  
        Geometry cutoffLoodlijn = loodLijn.intersection(safetyZone);
        cutoffLoodlijn = cutoffLoodlijn.difference(ignition);
        gs.put(createFeature(cutoffLoodlijn, "safetyDistance", (int)cutoffLoodlijn.getLength() + " m"));

        // 2. afstand tussen rand afsteekzone en safetyzone: haaks op publiek
        
        Coordinate[] endContinuousLine = {ignitionCentroid.getCoordinate(), eindLoodlijn.getCoordinate()};
        LineString continuousLine = gf.createLineString(endContinuousLine);
        Geometry cutoffContLine = continuousLine.intersection(safetyZone);
        cutoffContLine = cutoffContLine.difference(ignition);

        gs.put(createFeature(cutoffContLine, "safetyDistance", (int)cutoffContLine.getLength() + " m"));
    }

    public Geometry createEllipse(Coordinate startPoint, double rotation, double fanlength, double fanheight, int numPoints) throws TransformException {
     

        GeometricShapeFactory gsf = new GeometricShapeFactory(gf);
        gsf.setBase(new Coordinate(startPoint.x - fanlength, startPoint.y - fanheight));
        gsf.setWidth(fanlength * 2);
        gsf.setHeight(fanheight * 2);
        gsf.setNumPoints(numPoints);
        gsf.setRotation(rotation);

        Geometry ellipse = gsf.createEllipse();
        return ellipse;
    }

    private void calculateNormalSafetyZone(JSONObject feature, JSONObject audienceObj, JSONArray gs) throws ParseException, TransformException {
        Geometry ignition = wkt.read(feature.getString("wktgeom"));
        Geometry audience = wkt.read(audienceObj.getString("wktgeom"));
        Geometry zone = createNormalSafetyZone(feature,ignition);
        gs.put(createFeature(zone, "safetyZone", ""));
        createSafetyDistances(gs, audience, ignition, zone);
    }

    private Geometry createNormalSafetyZone(JSONObject feature, Geometry ignition) throws ParseException {
        JSONObject attributes = feature.getJSONObject("attributes");
        Integer zoneDistance;

        if (attributes.getString("fireworks_type").equals("consumer")) {
            zoneDistance = attributes.getInt("zonedistance_consumer_m");
        } else {
            zoneDistance = attributes.getInt("zonedistance_professional_m");
        }

        Geometry zone = ignition.buffer(zoneDistance);
        return zone;
    }

    private JSONObject createFeature(Geometry geom, String type, String label) {

        JSONObject feat = new JSONObject();
        JSONObject attrs = new JSONObject();
        feat.put("attributes", attrs);
        feat.put("wktgeom", geom.toText());

        attrs.put("type", type);
        attrs.put("label", label);

        return feat;
    }

    public Resolution print() {
        JSONObject result = new JSONObject();
        result.put("type", "print");
        return new StreamingResolution("application/json", new StringReader(result.toString()));
    }

    // <editor-fold defaultstate="collapsed" desc="Getters and setters">
    @Override
    public ActionBeanContext getContext() {
        return context;
    }

    @Override
    public void setContext(ActionBeanContext context) {
        this.context = context;
    }

    public String getFeatures() {
        return features;
    }

    public void setFeatures(String features) {
        this.features = features;
    }

    // </editor-fold>

}
