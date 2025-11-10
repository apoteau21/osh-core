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

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import org.sensorhub.api.common.BigId;
import org.sensorhub.api.common.IdEncoders;
import org.sensorhub.api.data.IDataStreamInfo;
import org.sensorhub.api.data.IObsData;
import org.sensorhub.api.data.ObsData;
import org.sensorhub.api.datastore.obs.DataStreamKey;
import org.sensorhub.api.datastore.obs.IObsStore;
import org.sensorhub.impl.service.consys.ResourceParseException;
import org.sensorhub.impl.service.consys.SWECommonUtils;
import org.sensorhub.impl.service.consys.ServiceErrors;
import org.sensorhub.impl.service.consys.obs.ObsHandler.ObsHandlerContextData;
import org.sensorhub.impl.service.consys.resource.PropertyFilter;
import org.sensorhub.impl.service.consys.resource.RequestContext;
import org.sensorhub.impl.service.consys.resource.ResourceBindingJson;
import org.sensorhub.impl.service.consys.resource.ResourceLink;
import org.sensorhub.utils.SWEDataUtils;
import org.sensorhub.api.datastore.feature.IFoiStore;
import org.vast.data.DataBlockMixed;
import org.vast.ogc.gml.IFeature;

import org.vast.cdm.common.DataStreamWriter;
import org.vast.swe.BinaryDataWriter;
import org.vast.swe.SWEConstants;
import org.vast.swe.ScalarIndexer;
import org.vast.swe.fast.JsonDataParserGson;
import org.vast.swe.fast.JsonDataWriterGson;
import org.vast.swe.helper.GeoPosHelper;
import org.vast.util.ReaderException;

import java.io.IOException;
import java.io.OutputStream;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;

import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Coordinate;

import org.sensorhub.api.datastore.EmptyFilterIntersection;
import com.google.common.collect.Sets;


import static org.sensorhub.impl.service.consys.SWECommonUtils.OM_COMPONENTS_FILTER;


public class ObsBindingGeoJson extends ResourceBindingJson<BigId, IObsData>
{
    ObsHandlerContextData contextData;
    IObsStore obsStore;
    JsonDataParserGson resultReader;
    Map<BigId, DataStreamWriter> resultWriters;
    ScalarIndexer timeStampIndexer;

    IFoiStore foiStore;

    private static final Set<String> LOCATION_DEFINITIONS = Sets.newHashSet(
            SWEConstants.DEF_SENSOR_LOC,
            SWEConstants.DEF_PLATFORM_LOC,
            SWEConstants.DEF_SAMPLING_LOC,
            GeoPosHelper.DEF_LOCATION
    );


    public ObsBindingGeoJson(RequestContext ctx, IdEncoders idEncoders, boolean forReading, IObsStore obsStore) throws IOException
    {
        super(ctx, idEncoders, forReading);
        this.contextData = (ObsHandlerContextData)ctx.getData();
        this.obsStore = obsStore;
        
        if (forReading)
        {
            resultReader = getSweCommonParser(contextData.dsInfo, reader);
            resultReader.setRenewDataBlock(true);
            timeStampIndexer = SWEDataUtils.getTimeStampIndexer(contextData.dsInfo.getRecordStructure());
        }
        else
        {
            this.resultWriters = new HashMap<>();
            
            // init result writer only in case of single datastream
            // otherwise we'll do it later
            if (contextData != null && contextData.dsInfo != null)
            {
                var resultWriter = getSweCommonWriter(contextData.dsInfo, writer, ctx.getPropertyFilter());
                resultWriters.put(ctx.getParentID(), resultWriter);
            }
        }
    }
    
    
    @Override
    public IObsData deserialize(JsonReader reader) throws IOException
    {
        // if array, prepare to parse first element
        if (reader.peek() == JsonToken.BEGIN_ARRAY)
            reader.beginArray();
        
        if (reader.peek() == JsonToken.END_DOCUMENT || !reader.hasNext())
            return null;
        
        var obs = new ObsData.Builder()
            .withDataStream(contextData.dsID);
        
        try
        {
            reader.beginObject();
            
            while (reader.hasNext())
            {
                var propName = reader.nextName();
                
                if ("phenomenonTime".equals(propName))
                    obs.withPhenomenonTime(OffsetDateTime.parse(reader.nextString()).toInstant());
                else if ("resultTime".equals(propName))
                    obs.withResultTime(OffsetDateTime.parse(reader.nextString()).toInstant());
                else if ("foi@id".equals(propName))
                {
                    try
                    {
                        var foiID = idEncoders.getFoiIdEncoder().decodeID(reader.nextString());
                        obs.withFoi(foiID);
                    }
                    catch (IllegalArgumentException e)
                    {
                        throw ServiceErrors.badRequest("Invalid FOI ID");
                    }
                }
                else if ("result".equals(propName))
                {
                    var result = resultReader.parseNextBlock();
                    obs.withResult(result);
                }
                else
                    reader.skipValue();
            }
            
            reader.endObject();
        }
        catch (DateTimeParseException e)
        {
            throw new ResourceParseException(INVALID_JSON_ERROR_MSG + "Invalid ISO8601 date/time at " + reader.getPath());
        }
        catch (IllegalStateException | ReaderException e)
        {
            throw new ResourceParseException(INVALID_JSON_ERROR_MSG + e.getMessage());
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

    // only shows anything if theres any lon/lat data
    @Override
    public void serialize(BigId key, IObsData obs, boolean showLinks, JsonWriter writer) throws IOException {

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

        double altitude = 0;
        double longitude = 0;
        double latitude = 0;

        for (int index : locationComponents) {
            var locationDataBlock = ((DataBlockMixed) obs.getResult()).getUnderlyingObject()[index];
            // You'll still need to check if these are real values and not null
            latitude = locationDataBlock.getDoubleValue(0);
            longitude = locationDataBlock.getDoubleValue(1);
            altitude = locationDataBlock.getDoubleValue(2); //geojson format doesnt seem to have alt :?
        }

        var obsId = idEncoders.getObsIdEncoder().encodeID(key);

        var resultWriter = resultWriters.computeIfAbsent(obs.getDataStreamID(),
                k -> getSweCommonWriter(k, writer, ctx.getPropertyFilter()) );

        var obsRes = obs.getResult().toString();

        var obsName = dataStream.getOutputName();

        if (!(longitude == 0.0 && latitude == 0.0)) {
            writer.beginObject();
            writer.name("type").value("Feature");

            writer.name("geometry");
            writer.beginObject();
            writer.name("type").value("Point");

            writer.name("coordinates").value("[" + longitude + ", " + latitude + "]");


            writer.name("properties");
            // name, id, timestamp, whatever else

            writer.beginObject();
            writer.name("name").value(obsName);
            writer.name("id").value(obsId);
            writer.name("phenomenonTime").value(obs.getPhenomenonTime().toString());
            writer.name("resultTime").value(obs.getResultTime().toString());

            // write if JSON is supported, otherwise print warning message
//        if (resultWriter instanceof JsonDataWriterGson)
//            resultWriter.write(obs.getResult());
//        else
//            writer.value("Compressed binary result not shown in JSON");

            writer.endObject();
            writer.endObject();
            writer.endObject();
            writer.flush();
        }
    }

    
    protected JsonWriter getJsonWriter(OutputStream os, PropertyFilter propFilter) throws IOException
    {
        var writer = super.getJsonWriter(os, propFilter);
        writer.setSerializeNulls(true);
        return writer;
    }
    
    
    protected DataStreamWriter getSweCommonWriter(BigId dsID, JsonWriter writer, PropertyFilter propFilter)
    {
        var dsInfo = obsStore.getDataStreams().get(new DataStreamKey(dsID));
        return getSweCommonWriter(dsInfo, writer, propFilter);
    }
    
    
    protected DataStreamWriter getSweCommonWriter(IDataStreamInfo dsInfo, JsonWriter writer, PropertyFilter propFilter)
    {        
        if (!SWECommonUtils.allowNonBinaryFormat(dsInfo.getRecordStructure(), dsInfo.getRecordEncoding()))
            return new BinaryDataWriter();
        
        // create JSON SWE writer
        var sweWriter = new JsonDataWriterGson(writer);
        sweWriter.setDataComponents(dsInfo.getRecordStructure());
        
        // filter out components that are already included in O&M
        sweWriter.setDataComponentFilter(OM_COMPONENTS_FILTER);
        return sweWriter;
    }
    
    
    protected JsonDataParserGson getSweCommonParser(IDataStreamInfo dsInfo, JsonReader reader)
    {
        // create JSON SWE parser
        var sweParser = new JsonDataParserGson(reader);
        sweParser.setDataComponents(dsInfo.getRecordStructure());
        
        // filter out components that are already included in O&M
        sweParser.setDataComponentFilter(OM_COMPONENTS_FILTER);
        return sweParser;
    }


    @Override
    public void startCollection() throws IOException
    {
        if (reader != null) {
            // if we're reading, just skip to the items array
            // calls to deserialize() will take it from there
            // TODO generalize this to all bindings
            reader.beginObject();
            while (reader.hasNext()) {
                var propName = reader.nextName();
                if (propName.equals(getItemsPropertyName()))
                    return;
                else
                    reader.skipValue();
            }
        }
        else
            startJsonCollection(writer);
    }


    @Override
    public void endCollection(Collection<ResourceLink> links) throws IOException
    {
        endJsonCollection(writer, links);
    }
}
