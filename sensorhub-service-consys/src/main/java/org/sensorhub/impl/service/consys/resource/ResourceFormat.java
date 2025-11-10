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

import java.util.Objects;
import org.vast.ogc.gml.GeoJsonBindings;
import net.opengis.swe.v20.BinaryBlock;
import net.opengis.swe.v20.BinaryEncoding;
import net.opengis.swe.v20.DataEncoding;


public class ResourceFormat
{
    public static String SHORT_JSON = "json";
    public static String SHORT_GEOJSON = "geojson";
    public static String SHORT_SMLJSON = "sml3";
    public static String SHORT_SMLXML = "sml2";
    public static String SHORT_HTML = "html";
    private static String SHORT_GEO_JSON = "geo_json";
    public static String SHORT_COTXML = "cot";
    
    public static ResourceFormat HTML = new ResourceFormat("text/html", SHORT_HTML);
    public static ResourceFormat JSON = new ResourceFormat("application/json", SHORT_JSON);
    public static ResourceFormat GEOJSON = new ResourceFormat(GeoJsonBindings.MIME_TYPE, SHORT_GEOJSON);
    
    public static ResourceFormat SML_JSON = new ResourceFormat("application/sml+json", SHORT_SMLJSON);
    public static ResourceFormat SML_XML = new ResourceFormat("application/sml+xml", SHORT_SMLXML);

    //BOW
    public static ResourceFormat GEO_JSON = new ResourceFormat("application/geo+json", SHORT_GEO_JSON);

    //BOW (again)
    public static ResourceFormat COT_XML = new ResourceFormat("application/cot+xml", SHORT_COTXML);

    public static ResourceFormat OM_JSON = new ResourceFormat("application/om+json", SHORT_JSON);
    public static ResourceFormat OM_XML = new ResourceFormat("application/om+xml");
    
    public static String SWE_FORMAT_PREFIX = "application/swe+";
    public static ResourceFormat SWE_JSON = new ResourceFormat(SWE_FORMAT_PREFIX + "json");
    public static ResourceFormat SWE_XML = new ResourceFormat(SWE_FORMAT_PREFIX + "xml");
    public static ResourceFormat SWE_BINARY = new ResourceFormat(SWE_FORMAT_PREFIX + "binary");
    public static ResourceFormat SWE_TEXT = new ResourceFormat(SWE_FORMAT_PREFIX + "csv");
    
    public static ResourceFormat TEXT_PLAIN = new ResourceFormat("text/plain");
    public static ResourceFormat TEXT_CSV = new ResourceFormat("text/csv");
    public static ResourceFormat TEXT_XML = new ResourceFormat("text/xml");
    public static ResourceFormat APPLI_XML = new ResourceFormat("application/xml");
    
    public static ResourceFormat AUTO = new ResourceFormat("auto");
    
    
    final String mimeType;
    final String shortName;
    
    
    public static ResourceFormat fromMimeType(String mimeType)
    {
        if (mimeType == null)
            return null;
        
        return new ResourceFormat(mimeType);
    }
    
    
    public static ResourceFormat fromShortName(String format)
    {
        if (SHORT_JSON.equals(format))
            return ResourceFormat.JSON;
        else if (SHORT_GEOJSON.equals(format))
            return ResourceFormat.GEOJSON;
        else if (SHORT_GEO_JSON.equals(format))
            return ResourceFormat.GEO_JSON;
        else if (SHORT_SMLJSON.equals(format))
            return ResourceFormat.SML_JSON;
        else if (SHORT_SMLXML.equals(format))
            return ResourceFormat.SML_XML;
        else if (SHORT_COTXML.equals(format))
            return ResourceFormat.COT_XML;
        else if (SHORT_HTML.equals(format))
            return ResourceFormat.HTML;
        else
            return null;
    }
    
    
    private ResourceFormat(String mimeType)
    {
        this.mimeType = mimeType;
        this.shortName = null;
    }
    
    
    private ResourceFormat(String mimeType, String shortName)
    {
        this.mimeType = mimeType;
        this.shortName = shortName;
    }
    
    
    public String getMimeType()
    {
        return mimeType;
    }
    
    
    public String getShortName()
    {
        if (shortName != null)
            return shortName;
        else
            return mimeType;
    }
    
    
    public boolean equals(ResourceFormat format)
    {
        return Objects.equals(format.mimeType, mimeType);
    }
    
    
    public boolean isOneOf(ResourceFormat... formats)
    {
        for (var f: formats)
        {
            if (f.equals(this))
                return true;
        }
        
        return false;
    }
    
    
    public static boolean allowNonBinaryFormat(DataEncoding encoding)
    {
        if (encoding instanceof BinaryEncoding)
        {
            var enc = (BinaryEncoding)encoding;
            for (var member: enc.getMemberList())
            {
                if (member instanceof BinaryBlock)
                    return false;
            }
        }
        
        return true;
    }
    
    
    public String toString()
    {
        return mimeType;
    }


    @Override
    public int hashCode()
    {
        return Objects.hash(mimeType);
    }


    @Override
    public boolean equals(Object obj)
    {
        return obj instanceof ResourceFormat &&
               Objects.equals(this.mimeType, ((ResourceFormat)obj).mimeType);
    }
    
}
