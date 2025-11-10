/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2020 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service.consys.obs;

import com.ctc.wstx.api.WstxOutputProperties;
import com.google.common.collect.Sets;
import javax.xml.stream.XMLStreamWriter;

//import org.apache.commons.io.output.XmlStreamWriter;
import org.sensorhub.api.common.BigId;
import org.sensorhub.api.common.IdEncoders;
import org.sensorhub.api.data.IDataStreamInfo;
import org.sensorhub.api.data.IObsData;
import org.sensorhub.api.data.ObsData;
import org.sensorhub.api.datastore.feature.IFoiStore;
import org.sensorhub.api.datastore.obs.DataStreamKey;
import org.sensorhub.api.datastore.obs.IObsStore;
import org.sensorhub.impl.service.consys.ResourceParseException;
import org.sensorhub.impl.service.consys.SWECommonUtils;
import org.sensorhub.impl.service.consys.ServiceErrors;
import org.sensorhub.impl.service.consys.obs.ObsHandler.ObsHandlerContextData;
import org.sensorhub.impl.service.consys.resource.PropertyFilter;
import org.sensorhub.impl.service.consys.resource.RequestContext;
import org.sensorhub.impl.service.consys.resource.ResourceBindingXml;
import org.sensorhub.impl.service.consys.resource.ResourceLink;
import org.sensorhub.utils.SWEDataUtils;
import org.vast.data.DataBlockMixed;
import org.vast.data.XMLEncodingImpl;
import org.vast.swe.SWEConstants;
import org.vast.swe.ScalarIndexer;
import org.vast.swe.fast.*;
import org.vast.swe.helper.GeoPosHelper;
import org.vast.util.ReaderException;
import org.vast.xml.DOMHelper;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;

import static org.sensorhub.impl.service.consys.SWECommonUtils.OM_COMPONENTS_FILTER;

public class ObsBindingCotXml extends ResourceBindingXml<BigId, IObsData> {
    ObsHandlerContextData contextData;
    IObsStore obsStore;
    XMLStreamReader resultReader;
    Map<BigId, AbstractDataWriter> resultWriters;
    DOMHelper dom;
    ScalarIndexer timeStampIndexer;
    XMLOutputFactory factory = XMLOutputFactory.newInstance();
    //CotDataWriter cotWriter;
    OutputStream os;

    IFoiStore foiStore;

    private static final Set<String> LOCATION_DEFINITIONS = Sets.newHashSet(
            SWEConstants.DEF_SENSOR_LOC,
            SWEConstants.DEF_PLATFORM_LOC,
            SWEConstants.DEF_SAMPLING_LOC,
            GeoPosHelper.DEF_LOCATION
    );

    protected ObsBindingCotXml(RequestContext ctx, IdEncoders idEncoders, boolean forReading, IObsStore obsStore) throws IOException, XMLStreamException {
        super(ctx, idEncoders, forReading);
        this.contextData = (ObsHandlerContextData)ctx.getData();
        this.obsStore = obsStore;

        dom = new DOMHelper();

        if (forReading)
        {
            var inputStream = ctx.getInputStream();
            resultReader = (XMLStreamReader) getSweCommonParser(contextData.dsInfo, inputStream);
            resultReader.nextTag();

            timeStampIndexer = SWEDataUtils.getTimeStampIndexer(contextData.dsInfo.getRecordStructure());
        }
        else
        {
            this.resultWriters = new HashMap<>();

            // init result writer only in case of single datastream
            // otherwise we'll do it later
            if (contextData != null && contextData.dsInfo != null)
            {
                var resultWriter = getSweCommonWriter(contextData.dsInfo, os);
                resultWriters.put(ctx.getParentID(), resultWriter);
            }
        }
    }

    @Override
    public IObsData deserialize(XmlDataParser xmlReader) throws IOException {
        var obs = new ObsData.Builder()
            .withDataStream(contextData.dsID);

        try {
            while (resultReader.hasNext()) {
                resultReader.next();

                //var propName = reader.nextName();

                if (resultReader.isStartElement()) {
                    String propName = resultReader.getLocalName();

                    if ("phenomenonTime".equals(propName))
                        obs.withPhenomenonTime(OffsetDateTime.parse(resultReader.getElementText()).toInstant());
                    else if ("resultTime".equals(propName))
                        obs.withResultTime(OffsetDateTime.parse(resultReader.getElementText()).toInstant());
                    else if ("foi@id".equals(propName)) {
                        try {
                            var foiID = idEncoders.getFoiIdEncoder().decodeID(resultReader.getElementText());
                            obs.withFoi(foiID);
                        } catch (IllegalArgumentException e) {
                            throw ServiceErrors.badRequest("Invalid FOI ID");
                        } catch (XMLStreamException e) {
                            throw new RuntimeException(e);
                        }
                    } else if ("result".equals(propName)) {
                        var result = xmlReader.parseNextBlock();
                        obs.withResult(result);
                    }
                }

            }
        }
        catch (DateTimeParseException e)
        {
            throw new ResourceParseException(INVALID_XML_ERROR_MSG + "Invalid ISO8601 date/time at " + resultReader.getLocalName());
        }
        catch (IllegalStateException | ReaderException e)
        {
            throw new ResourceParseException(INVALID_XML_ERROR_MSG + e.getMessage());
        } catch (XMLStreamException e) {
            throw new RuntimeException(e);
        }

        if (contextData.foiId != null && contextData.foiId != BigId.NONE)
            obs.withFoi(contextData.foiId);

        var newObs = obs.build();

        // set timestamp in result data if present in schema
        if (timeStampIndexer != null)
        {
            var phenomenonTimeIdx = timeStampIndexer.getDataIndex(newObs.getResult());
            newObs.getResult().setDoubleValue(phenomenonTimeIdx, newObs.getPhenomenonTime().toEpochMilli() / 1000.0);
        }

        return newObs;
    }

    @Override
    public void serialize(BigId key, IObsData res, boolean showLinks, XmlDataWriter xmlWriter) throws IOException, XMLStreamException {}



    @Override
    public void serialize(BigId key, IObsData obs, boolean showLinks, CotDataWriter cotWriter) throws IOException, XMLStreamException {

        Set<Integer> locationComponents = new HashSet<>();

        var dataStream = this.obsStore.getDataStreams().get(new DataStreamKey(obs.getDataStreamID()));
        for (int i = 0; i < dataStream.getRecordStructure().getComponentCount(); i++) {
            var component = dataStream.getRecordStructure().getComponent(i);
            if (LOCATION_DEFINITIONS.contains(component.getDefinition())) {
                // This is how we know we have location components in the data structure
                // So we can save this and parse specifically the location components into GeoJSON
                locationComponents.add(i);
            }
        }

        double longitude = 0;
        double latitude = 0;

        for (int index : locationComponents) {
            var locationDataBlock = ((DataBlockMixed) obs.getResult()).getUnderlyingObject()[index];
            // You'll still need to check if these are real values and not null
            latitude = locationDataBlock.getDoubleValue(0);
            longitude = locationDataBlock.getDoubleValue(1);
        }

        var obsId = idEncoders.getObsIdEncoder().encodeID(key);

        var obsName = dataStream.getOutputName();

        if (!(longitude == 0.0 && latitude == 0.0)) {
            factory.setProperty(WstxOutputProperties.P_OUTPUT_VALIDATE_STRUCTURE, false);
            XMLOutputFactory factory = XMLOutputFactory.newInstance();
            cotWriter = (CotDataWriter) factory.createXMLStreamWriter(System.out);
            cotWriter.writeStartElement("<detail>");

            cotWriter.flush();
        }
    }

    protected CotDataWriter getCotWriter(OutputStream os, PropertyFilter propFilter) throws IOException {
        var writer = super.getCotWriter(os, propFilter);
        writer.setSerializeNulls(true);
        return writer;
    }

    protected AbstractDataWriter getSweCommonWriter(BigId dsID, OutputStream os) throws IOException {
        var dsInfo = obsStore.getDataStreams().get(new DataStreamKey(dsID));

        return getSweCommonWriter(dsInfo, os);
    }

    protected AbstractDataWriter getSweCommonWriter(IDataStreamInfo dsInfo, OutputStream os) throws IOException {
        if (!SWECommonUtils.allowNonBinaryFormat(dsInfo.getRecordStructure(), dsInfo.getRecordEncoding()))
            return new BinaryDataWriter();

////        if (dsInfo.getRecordEncoding() instanceof TextEncodingImpl) {
////            throw new IOException("Text encoding not supported for application/cot+xml");
////        }

        // create cot/xml writer
        CotDataWriter cotWriter = new CotDataWriter(); //wraps writer. when you return writer,
        // youre passing in a writer that knows how to write the specific xml data it needs.
        //CotWriter responsible for writing cot spec compliant info

        //xmlcotwriter extends xmldatawriter. new class. same with xmlcotparser and reader. convert it back into a swecommondata record that can be put in osh

        cotWriter.setDataEncoding(new XMLEncodingImpl());
        cotWriter.setOutput(os);
        cotWriter.setDataComponents(dsInfo.getRecordStructure());
        //cotWriter.setDataEncoding(dsInfo.getRecordEncoding());

        // filter out components that are already included in O&M
        cotWriter.setDataComponentFilter(OM_COMPONENTS_FILTER);
        return cotWriter;
    }

    protected XmlDataParser getSweCommonParser(IDataStreamInfo dsInfo, InputStream is) throws IOException {
        //should be cotxmlparser... soon

        // create XML SWE parser
        var sweParser = new XmlDataParser();
        sweParser.setDataComponents(dsInfo.getRecordStructure());

        // filter out components that are already included in O&M
        sweParser.setDataComponentFilter(OM_COMPONENTS_FILTER);
        sweParser.setInput(is);
        return sweParser;
    }

    @Override
    public void startCollection() throws XMLStreamException {
        if (resultReader != null) {
            // if we're reading, just skip to the items array
            // calls to deserialize() will take it from there
            // TODO generalize this to all bindings
            resultReader.next();
            while (resultReader.hasNext()) {
                var propName = resultReader.getName();
                if (propName.equals(getItemsPropertyName()))
                    return;
                else
                    resultReader.next();
            }
        }
        else
            startXMLCollection((XmlDataWriter) resultReader);
   }


    @Override
    public void endCollection(Collection<ResourceLink> links) throws IOException
    {
        resultReader.isEndElement();
        // resultReader.getText();
    }
}


