/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2020 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service.consys.sensorml;

import java.io.IOException;
import java.util.Collection;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;

import org.sensorhub.api.common.BigId;
import org.sensorhub.api.common.IdEncoders;
import org.sensorhub.api.data.IObsData;
import org.sensorhub.api.datastore.feature.FeatureKey;
import org.sensorhub.api.feature.ISmlFeature;
import org.sensorhub.api.procedure.IProcedureWithDesc;
import org.sensorhub.api.system.ISystemWithDesc;
import org.sensorhub.impl.service.consys.ResourceParseException;
import org.sensorhub.impl.service.consys.resource.RequestContext;
import org.sensorhub.impl.service.consys.resource.ResourceBindingXml;
import org.sensorhub.impl.service.consys.resource.ResourceFormat;
import org.sensorhub.impl.service.consys.resource.ResourceLink;
import org.sensorhub.impl.system.wrapper.SmlFeatureWrapper;
import org.vast.sensorML.SMLStaxBindings;
import net.opengis.sensorml.v20.AbstractProcess;
import net.opengis.sensorml.v20.Deployment;
import org.vast.swe.fast.CotDataWriter;
import org.vast.swe.fast.XmlDataParser;
import org.vast.swe.fast.XmlDataWriter;


/**
 * <p>
 * SensorML XML formatter for system resources
 * </p>
 * 
 * @param <V> Type of SML feature resource
 *
 * @author Alex Robin
 * @since Jan 26, 2021
 */
public class SmlProcessBindingSmlXml<V extends ISmlFeature<?>> extends ResourceBindingXml<FeatureKey, V>
{
    SMLStaxBindings smlBindings;
    
    
    public SmlProcessBindingSmlXml(RequestContext ctx, IdEncoders idEncoders, boolean forReading) throws IOException
    {
        super(ctx, idEncoders, forReading);
        
        try
        {
            this.smlBindings = new SMLStaxBindings();
            if (!forReading)
            {
                smlBindings.setNamespacePrefixes(xmlWriter);
                smlBindings.declareNamespacesOnRootElement();
            }
        }
        catch (XMLStreamException e)
        {
            throw new IOException("Error initializing XML bindings", e);
        }
    }

    @Override
    public V deserialize(XmlDataParser xmlReader) throws IOException {
        return null;
    }

    @Override
    public void serialize(FeatureKey key, V res, boolean showLinks, XmlDataWriter xmlWriter) throws IOException, XMLStreamException {

    }

    @Override
    public V deserialize(XMLStreamReader xmlReader) throws IOException {
        return null;
    }

    @Override
    public void serialize(FeatureKey key, V res, boolean showLinks, XMLStreamWriter xmlWriter) throws IOException {

    }


    @Override
    public V deserialize() throws IOException
    {
        try
        {
            // skip all events until we reach the next XML element or abort.
            // This is needed because deserialize() is called multiple times and nextTag()
            // breaks if there is no more non-whitespace content.
            while (xmlReader.getEventType() != XMLStreamConstants.START_ELEMENT)
            {                
                if (!xmlReader.hasNext())
                    return null;
                else
                    xmlReader.next();
            }
                        
            var sml = smlBindings.readDescribedObject(xmlReader);
            
            if (sml instanceof Deployment)
            {
                @SuppressWarnings("unchecked")
                var wrapper = (V)new DeploymentAdapter(sml);
                return wrapper;
            }
            else
            {
                @SuppressWarnings("unchecked")
                var wrapper = (V)new SmlFeatureWrapper((AbstractProcess)sml);
                return wrapper;
            }
        }
        catch (XMLStreamException e)
        {
            throw new ResourceParseException(INVALID_XML_ERROR_MSG + e.getMessage(), e);
        }
    }


    @Override
    public void serialize(FeatureKey key, V res, boolean showLinks) throws IOException
    {
        ctx.setResponseContentType(ResourceFormat.APPLI_XML.getMimeType());
        
        try
        {
            try
            {
                var sml = res.getFullDescription();
                if (sml == null)
                {
                    if (res instanceof ISystemWithDesc)
                        sml = new SMLConverter().genericFeatureToSystem(res);
                    else if (res instanceof IProcedureWithDesc)
                        sml = new SMLConverter().genericFeatureToProcedure(res);
                    else
                        throw new IOException("Cannot convert feature to SensorML");
                }
                
                smlBindings.writeDescribedObject(xmlWriter, sml);
                xmlWriter.flush();
            }
            catch (Exception e)
            {
                IOException wrappedEx = new IOException("Error writing SensorML XML", e);
                throw new IllegalStateException(wrappedEx);
            }
        }
        catch (IllegalStateException e)
        {
            if (e.getCause() instanceof IOException)
                throw (IOException)e.getCause();
            else
                throw e;
        }   
    }

    @Override
    public void serialize(BigId key, IObsData obs, boolean showLinks, CotDataWriter cotWriter) throws IOException, XMLStreamException {

    }


    @Override
    public void startCollection() throws IOException {
        try
        {
            xmlWriter.writeStartElement("systems");
            smlBindings.writeNamespaces(xmlWriter);
        }
        catch (XMLStreamException e)
        {
            throw new IOException(e);
        }
    }


    @Override
    public void endCollection(Collection<ResourceLink> links) throws IOException
    {
        try
        {
            xmlWriter.writeEndElement();
            xmlWriter.writeEndDocument();
        }
        catch (XMLStreamException e)
        {
            throw new IOException(e);
        }
    }
}
