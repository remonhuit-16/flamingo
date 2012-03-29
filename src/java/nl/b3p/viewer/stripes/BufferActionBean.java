/*
 * Copyright (C) 2012 Expression organization is undefined on line 4, column 61 in Templates/Licenses/license-gpl30.txt.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package nl.b3p.viewer.stripes;

import com.vividsolutions.jts.geom.Geometry;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import javax.servlet.http.HttpServletResponse;
import net.sourceforge.stripes.action.*;
import net.sourceforge.stripes.validation.Validate;
import nl.b3p.viewer.config.services.GeoService;
import nl.b3p.viewer.config.services.Layer;
import nl.b3p.viewer.image.Bbox;
import nl.b3p.viewer.image.CombineImageSettings;
import nl.b3p.viewer.image.CombineImageWkt;
import nl.b3p.viewer.image.ImageTool;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.geotools.data.FeatureSource;
import org.geotools.data.Query;
import org.geotools.factory.CommonFactoryFinder;
import org.geotools.feature.FeatureIterator;
import org.opengis.filter.Filter;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.filter.FilterFactory2;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.stripesstuff.stripersist.Stripersist;

/**
 *
 * @author Meine Toonen meinetoonen@b3partners.nl
 */
@UrlBinding("/action/Buffer")
@StrictBinding
public class BufferActionBean implements ActionBean {

    private static final Log log = LogFactory.getLog(BufferActionBean.class);
    private ActionBeanContext context;
    @Validate
    private String bbox;
    @Validate
    private Long serviceId;
    @Validate
    private String layerName;
    @Validate
    private Integer width;
    @Validate
    private Integer height;
    @Validate
    private Integer buffer;
    @Validate
    private Integer maxFeatures = 250;
    @Validate
    private String color;
    
    private final Integer MAX_FEATURES = 250;
    
    private static final String JSP = "/WEB-INF/jsp/error.jsp";
    
    //<editor-fold defaultstate="collapsed" desc="Getters and Setters">

    public ActionBeanContext getContext() {
        return context;
    }

    public void setContext(ActionBeanContext context) {
        this.context = context;
    }

    public String getBbox() {
        return bbox;
    }

    public void setBbox(String bbox) {
        this.bbox = bbox;
    }

    public String getLayerName() {
        return layerName;
    }

    public void setLayerName(String layerName) {
        this.layerName = layerName;
    }

    public Long getServiceId() {
        return serviceId;
    }

    public void setServiceId(Long serviceId) {
        this.serviceId = serviceId;
    }

    public Integer getHeight() {
        return height;
    }

    public void setHeight(Integer height) {
        this.height = height;
    }

    public Integer getWidth() {
        return width;
    }

    public void setWidth(Integer width) {
        this.width = width;
    }

    public Integer getBuffer() {
        return buffer;
    }

    public void setBuffer(Integer buffer) {
        this.buffer = buffer;
    }

    public Integer getMaxFeatures() {
        return maxFeatures;
    }

    public void setMaxFeatures(Integer maxFeatures) {
        this.maxFeatures = maxFeatures;
    }

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }

    //</editor-fold>
    
    @DefaultHandler
    public Resolution image() {

        try {
            final CombineImageSettings cis = new CombineImageSettings();
            cis.setBbox(bbox);
            cis.setWidth(width);
            cis.setHeight(height);
            Color c = Color.RED;
            if(color != null){
                c = Color.decode("#"+color);
            }
            cis.setDefaultWktGeomColor(c);

            List<CombineImageWkt> wkts = getFeatures(cis.getBbox());
            cis.setWktGeoms(wkts);
            
            final BufferedImage bi = ImageTool.drawGeometries(null, cis);

            StreamingResolution res = new StreamingResolution(cis.getMimeType()) {

                @Override
                public void stream(HttpServletResponse response) throws Exception {
                    ImageTool.writeImage(bi, cis.getMimeType(), response.getOutputStream());
                }
            };
            return res;
        } catch (Exception e) {
            log.error("Fout genereren layerimage", e);
            BufferedImage leeg = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE);
        }
        return new ForwardResolution(JSP);
    }


    private List<CombineImageWkt> getFeatures(Bbox bbox) throws Exception {
        List<CombineImageWkt> wkts = new ArrayList<CombineImageWkt>();
        GeoService gs = Stripersist.getEntityManager().find(GeoService.class, serviceId);
        Layer l = gs.getLayer(layerName);

        if (l.getFeatureType() == null) {
            throw new Exception("Layer has no feature type");
        }

        FeatureSource fs = l.getFeatureType().openGeoToolsFeatureSource();

        String geomAttribute = fs.getSchema().getGeometryDescriptor().getLocalName();


        CoordinateReferenceSystem crs = fs.getSchema().getGeometryDescriptor().getCoordinateReferenceSystem();

        FilterFactory2 ff = CommonFactoryFinder.getFilterFactory2();
        Filter filter = ff.bbox(ff.property(geomAttribute), new ReferencedEnvelope(bbox.getMinx(),bbox.getMaxx(), bbox.getMiny(),bbox.getMaxy(), crs));

        Query q = new Query(fs.getName().toString());
        q.setFilter(filter);
        q.setMaxFeatures(Math.min(maxFeatures, MAX_FEATURES));

        FeatureIterator<SimpleFeature> it = fs.getFeatures(q).features();

        try {
            while (it.hasNext()) {
                SimpleFeature f = it.next();
                Geometry g = (Geometry) f.getDefaultGeometry();
                g= g.buffer(buffer);
                wkts.add(new CombineImageWkt(g.toText()));
                System.out.println(f);
            }
        } finally {
            it.close();
            fs.getDataStore().dispose();
        }
        return wkts;
    }
}
