/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2020 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service.consys.resource;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Set;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;

import com.google.gson.JsonParseException;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonWriter;
import org.sensorhub.api.common.BigId;
import org.sensorhub.api.common.IdEncoders;
import org.sensorhub.api.data.IObsData;
import org.sensorhub.impl.service.consys.ServiceErrors;
import org.sensorhub.impl.service.consys.json.FilteredJsonWriter;
import org.vast.json.JsonInliningWriter;
import org.vast.swe.fast.CotDataWriter;
import org.vast.swe.fast.XmlDataWriter;
import org.vast.swe.fast.XmlDataParser;
import org.vast.xml.XMLImplFinder;


/**
 * <p>
 * Base class for all XML resource formatters
 * </p>
 * 
 * @param <K> Resource Key
 * @param <V> Resource Object
 *
 * @author Alex Robin
 * @since Jan 26, 2021
 */
public abstract class ResourceBindingXml<K, V> extends ResourceBinding<K, V>
{
    public static final String INVALID_XML_ERROR_MSG = "Invalid XML: ";
    public static final String MISSING_PROP_ERROR_MSG = "Missing property: ";
    
    protected final XmlDataParser xmlReader;
    protected final XmlDataWriter xmlWriter;

    protected boolean isCollection;

    Set<String> excludedProps;
    Set<String> includedProps;
    
    protected ResourceBindingXml(RequestContext ctx, IdEncoders idEncoders, boolean forReading) throws IOException
    {
        super(ctx, idEncoders);

        if (forReading)
        {
            var factory = XMLImplFinder.getStaxInputFactory();
            var is = new BufferedInputStream(ctx.getInputStream());
            // XmlDataParser created and set up
            xmlReader = new XmlDataParser();
           // xmlReader = factory.createXMLStreamReader(is, StandardCharsets.UTF_8.name()); // dont need this
            xmlWriter = null;
        }
        else
        {
            var factory = XMLImplFinder.getStaxOutputFactory();
            var os = ctx.getOutputStream();//new BufferedOutputStream(ctx.getOutputStream());
            // XmlDataWriter created and set up
            xmlWriter = new XmlDataWriter();
          //  xmlWriter = factory.createXMLStreamWriter(os, StandardCharsets.UTF_8.name()); // dont need this
            xmlReader = null;
        }
    }

    public abstract V deserialize(XmlDataParser xmlReader) throws IOException;
    public abstract void serialize(K key, V res, boolean showLinks, XmlDataWriter xmlWriter) throws IOException, XMLStreamException;
    public abstract void endCollection(Collection<ResourceLink> links) throws IOException;

    @Override
    public V deserialize() throws IOException
    {
        try
        {
            return deserialize(this.xmlReader);
        }
        catch (JsonParseException e)
        {
            throw ServiceErrors.invalidPayload(INVALID_XML_ERROR_MSG + e.getMessage());
        }
    }

    @Override
    public void serialize(K key, V res, boolean showLinks) throws IOException, XMLStreamException {
        serialize(key, res, showLinks, this.xmlWriter);
    }

    public abstract void serialize(BigId key, IObsData obs, boolean showLinks, CotDataWriter cotWriter) throws IOException, XMLStreamException;

    protected CotDataWriter getCotWriter(OutputStream os, PropertyFilter propFilter) throws IOException
    {
        CotDataWriter writer = new CotDataWriter();
        var osw = new OutputStreamWriter(os, StandardCharsets.UTF_8);
        if (propFilter != null) {
            this.excludedProps = propFilter.getExcludedProps();
            this.includedProps = propFilter.getIncludedProps();
        }
//        else
//            writer = new JsonInliningWriter(osw);

        writer.setStrictness(Strictness.LENIENT);
        writer.setSerializeNulls(false);
        writer.setIndent(INDENT);
        return writer;
    }

    @Override
    public void startCollection() throws XMLStreamException, IOException {
        isCollection = true;
        startXMLCollection(xmlWriter);
    }

    protected void startXMLCollection(XmlDataWriter xmlWriter) throws XMLStreamException {

        xmlWriter.writeStartElement(getItemsPropertyName());
       // xmlWriter.beginArray();
    }

    protected String getItemsPropertyName()
    {
        return "items";
    }

}