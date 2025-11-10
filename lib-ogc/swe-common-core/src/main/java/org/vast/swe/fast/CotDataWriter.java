/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2015 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.vast.swe.fast;

import com.ctc.wstx.api.WstxOutputProperties;
import com.google.gson.FormattingStyle;
import com.google.gson.Strictness;
import net.opengis.swe.v20.*;
import net.opengis.swe.v20.Boolean;
import org.vast.data.AbstractArrayImpl;
import org.vast.data.XMLEncodingImpl;
import org.vast.swe.SWEDataTypeUtils;
import org.vast.util.DateTimeFormat;
import org.vast.util.WriterException;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;


/**
 * <p>
 * New implementation of XML data writer with better efficiency since the 
 * write tree is pre-computed during init instead of being re-evaluated
 * while iterating through the component tree.
 * </p>
 *
 * @author Ashley Poteau
 * @since Oct 31, 2025
 */
public class CotDataWriter extends XmlDataWriter
{
    static final String COT_ERROR = "Error writing XML stream for ";
    
    protected XMLStreamWriter xmlWriter;
    protected String namespace;
    protected String prefix;
    protected Map<String, IntegerWriter> countWriters = new HashMap<>();
    private Strictness strictness = Strictness.LEGACY_STRICT;
    private boolean serializeNulls = true;
    private FormattingStyle formattingStyle;
    // These fields cache data derived from the formatting style, to avoid having to
    // re-evaluate it every time something is written
    private String formattedColon;
    private String formattedComma;
    private boolean usesEmptyNewlineAndIndent;


//    public XmlDataWriter(XMLStreamWriter xmlWriter) {
//        super();
//    }

    public final void setStrictness(Strictness strictness) {
        this.strictness = Objects.requireNonNull(strictness);
    }

    public final Strictness getStrictness() {
        return strictness;
    }

    public final void setSerializeNulls(boolean serializeNulls) {

        this.serializeNulls = serializeNulls;
    }

    /**
     * Returns true if object members are serialized when their value is null. This has no impact on
     * array elements. The default is true.
     */
    public final boolean getSerializeNulls() {
        return serializeNulls;
    }

    public final void setFormattingStyle(FormattingStyle formattingStyle) {
        this.formattingStyle = Objects.requireNonNull(formattingStyle);

        this.formattedComma = ",";
        if (this.formattingStyle.usesSpaceAfterSeparators()) {
            this.formattedColon = ": ";

            // Only add space if no newline is written
            if (this.formattingStyle.getNewline().isEmpty()) {
                this.formattedComma = ", ";
            }
        } else {
            this.formattedColon = ":";
        }

        this.usesEmptyNewlineAndIndent =
                this.formattingStyle.getNewline().isEmpty() && this.formattingStyle.getIndent().isEmpty();
    }

    public final void setIndent(String indent) {
        if (indent.isEmpty()) {
            setFormattingStyle(FormattingStyle.COMPACT);
        } else {
            setFormattingStyle(FormattingStyle.PRETTY.withIndent(indent));
        }
    }

    protected abstract class ValueWriter extends BaseProcessor
    {
        String eltName;
        
        public abstract void writeValue(DataBlock data, int index) throws XMLStreamException;
        
        @Override
        public int process(DataBlock data, int index) throws IOException
        {
            try
            {
                if (enabled)
                {
                    writeStartElement(eltName);
                    writeValue(data, index);
                    xmlWriter.writeEndElement();
                }
                
                return ++index;
            }
            catch (XMLStreamException e)
            {
                throw new WriterException(COT_ERROR + eltName + " value", e);
            }
        }
    }
    
    
    protected class BooleanWriter extends ValueWriter
    {
        public BooleanWriter(String eltName)
        {
            this.eltName = eltName;
        }
        
        @Override
        public void writeValue(DataBlock data, int index) throws XMLStreamException
        {
            boolean val = data.getBooleanValue(index);
            xmlWriter.writeCharacters(java.lang.Boolean.toString(val));
        }
    }
    
    
    protected class IntegerWriter extends ValueWriter
    {
        int val;
        
        public IntegerWriter(String eltName)
        {
            this.eltName = eltName;
        }
        
        @Override
        public void writeValue(DataBlock data, int index) throws XMLStreamException
        {
            val = data.getIntValue(index);
            xmlWriter.writeCharacters(Integer.toString(val));
        }
    }
    
    
    protected class DecimalWriter extends ValueWriter
    {
        public DecimalWriter(String eltName)
        {
            this.eltName = eltName;
        }
        
        @Override
        public void writeValue(DataBlock data, int index) throws XMLStreamException
        {
            double val = data.getDoubleValue(index);
            xmlWriter.writeCharacters(SWEDataTypeUtils.getDoubleOrInfAsString(val));
        }
    }
    
    
    protected class RoundingDecimalWriter extends DecimalWriter
    {
        public RoundingDecimalWriter(String eltName, int sigDigits)
        {
            super(eltName);
        }
    }
    
    
    protected class IsoDateTimeWriter extends ValueWriter
    {
        DateTimeFormat timeFormat = new DateTimeFormat();
        
        public IsoDateTimeWriter(String eltName)
        {
            this.eltName = eltName;
        }
        
        @Override
        public void writeValue(DataBlock data, int index) throws XMLStreamException
        {
            double val = data.getDoubleValue(index);
            xmlWriter.writeCharacters(timeFormat.formatIso(val, 0));
        }
    }
    
    
    protected class StringWriter extends ValueWriter
    {
        public StringWriter(String eltName)
        {
            this.eltName = eltName;
        }
        
        @Override
        public void writeValue(DataBlock data, int index) throws XMLStreamException
        {
            String val = data.getStringValue(index);
            xmlWriter.writeCharacters(val);
        }
    }
    
    
    protected class RangeWriter extends RecordProcessor
    {
        String eltName;
        
        public RangeWriter(String eltName)
        {
            this.eltName = eltName;
        }
        
        @Override
        public int process(DataBlock data, int index) throws IOException
        {
            try
            {
                if (enabled)
                    writeStartElement(eltName);
                
                fieldProcessors.get(0).process(data, index++);
                
                if (enabled)
                    xmlWriter.writeCharacters(" ");
                
                fieldProcessors.get(1).process(data, index++);
                
                if (enabled)
                    xmlWriter.writeEndElement();
                
                return index;
            }
            catch (XMLStreamException e)
            {
                throw new WriterException(COT_ERROR + eltName + " record", e);
            }
        }
    }
    
    
    protected class RecordWriter extends RecordProcessor
    {
        String eltName;
        
        public RecordWriter(String eltName)
        {
            this.eltName = eltName;
        }
        
        @Override
        public int process(DataBlock data, int index) throws IOException
        {
            try
            {
                if (enabled)
                    writeStartElement(eltName);
                
                int newIndex = super.process(data, index);
                
                if (enabled)
                    xmlWriter.writeEndElement();
                
                return newIndex;
            }
            catch (XMLStreamException e)
            {
                throw new WriterException(COT_ERROR + eltName + " record", e);
            }
        }
    }
    
    
    protected class ChoiceWriter extends ChoiceProcessor
    {
        String eltName;
        
        public ChoiceWriter(String eltName)
        {
            this.eltName = eltName;
        }
        
        @Override
        public int process(DataBlock data, int index) throws IOException
        {
            int selectedIndex = data.getIntValue(index);
            
            try
            {
                if (enabled)
                    writeStartElement(eltName);
                
                int newIndex = super.process(data, ++index, selectedIndex);
                
                if (enabled)
                    xmlWriter.writeEndElement();
                
                return newIndex;
            }
            catch (XMLStreamException e)
            {
                throw new WriterException(COT_ERROR + eltName + " choice", e);
            }
        }
    }
    
    
    protected class ArrayWriter extends ArrayProcessor
    {
        String eltName;
        
        public ArrayWriter(String eltName)
        {
            this.eltName = eltName;
        }
        
        @Override
        public int process(DataBlock data, int index) throws IOException
        {
            try
            {
                if (enabled)
                    writeStartElement(eltName);
                
                int arraySize = getArraySize();
                if (enabled)
                    xmlWriter.writeAttribute(AbstractArrayImpl.ELT_COUNT_NAME, Integer.toString(arraySize));
                
                for (int i = 0; i < arraySize; i++)
                    index = eltProcessor.process(data, index);
                
                if (enabled)
                    xmlWriter.writeEndElement();
                
                return index;
            }
            catch (XMLStreamException e)
            {
                throw new WriterException(COT_ERROR + eltName + " array", e);
            }
        }
    }


    
    public void writeStartElement(String eltName) throws XMLStreamException
    {
        if (namespace != null)
        {
            if (prefix != null)
                xmlWriter.writeStartElement(prefix, eltName, namespace);
            else
                xmlWriter.writeStartElement(namespace, eltName);
        }
        else
            xmlWriter.writeStartElement(eltName);
    }
    
    
    @Override
    protected void init()
    {
        namespace = ((XMLEncodingImpl)dataEncoding).getNamespace();
        prefix = ((XMLEncodingImpl)dataEncoding).getPrefix();
    }
    
    
    @Override
    public void setOutput(OutputStream os) throws IOException
    {
        try
        {
            XMLOutputFactory factory = XMLOutputFactory.newInstance();
            factory.setProperty(WstxOutputProperties.P_OUTPUT_VALIDATE_STRUCTURE, false);
            xmlWriter = factory.createXMLStreamWriter(os);
            //xmlWriter = new IndentingXMLStreamWriter(xmlWriter);
        }
        catch (XMLStreamException e)
        {
            throw new WriterException("Error while creating XML stream writer", e);
        }
    }
    
    
    @Override
    public void write(DataBlock data) throws IOException
    {
        try
        {
            super.write(data);
            xmlWriter.writeCharacters("\n");
        }
        catch (XMLStreamException e)
        {
            throw new IOException(e.getMessage(), e.getCause());
        }
    }
    
    
    @Override
    public void startStream(boolean addWrapper) throws IOException
    {
        try
        {
            if (addWrapper)
                xmlWriter.writeStartElement("records");
        }
        catch (XMLStreamException e)
        {
            throw new IOException(e.getMessage(), e.getCause());
        }
    }
    
    
    @Override
    public void endStream() throws IOException
    {
        try
        {
            xmlWriter.writeEndDocument();
        }
        catch (XMLStreamException e)
        {
            throw new IOException(e.getMessage(), e.getCause());
        }
    }
    

    @Override
    public void flush() throws IOException
    {
        try
        {
            if (xmlWriter != null)
                xmlWriter.flush();
        }
        catch (XMLStreamException e)
        {
            throw new WriterException("Error while flushing XML stream writer", e);
        }
    }
    

    @Override
    public void close() throws IOException
    {
        try
        {
            if (xmlWriter != null)
                xmlWriter.close();
        }
        catch (XMLStreamException e)
        {
            throw new WriterException("Error while closing XML stream writer", e);
        }
    }
    
    
    @Override
    public void visit(Boolean comp)
    {
        addToProcessorTree(new BooleanWriter(comp.getName()));
    }
    
    
    @Override
    public void visit(Count comp)
    {
        IntegerWriter writer = new IntegerWriter(comp.getName());
        if (comp.isSetId())
            countWriters.put(comp.getId(), writer);
        addToProcessorTree(writer);
    }
    
    
    @Override
    public void visit(Quantity comp)
    {
        if (comp.getConstraint() != null && comp.getConstraint().isSetSignificantFigures())
        {
            int sigFigures = comp.getConstraint().getSignificantFigures(); 
            addToProcessorTree(new RoundingDecimalWriter(comp.getName(), sigFigures));
        }
        else
            addToProcessorTree(new DecimalWriter(comp.getName()));
    }
    
    
    @Override
    public void visit(Time comp)
    {
        if (!comp.isIsoTime())
        {
            if (comp.getConstraint() != null && comp.getConstraint().isSetSignificantFigures())
            {
                int sigFigures = comp.getConstraint().getSignificantFigures(); 
                addToProcessorTree(new RoundingDecimalWriter(comp.getName(), sigFigures));
            }
            else
                addToProcessorTree(new DecimalWriter(comp.getName()));
        }
        else
            addToProcessorTree(new IsoDateTimeWriter(comp.getName()));
    }
    
    
    @Override
    public void visit(Category comp)
    {
        addToProcessorTree(new StringWriter(comp.getName()));
    }
    
    
    @Override
    public void visit(Text comp)
    {
        addToProcessorTree(new StringWriter(comp.getName()));
    }
    
    
    @Override
    protected AtomProcessor getRangeProcessor(RangeComponent range)
    {
        return new RangeWriter(range.getName());
    }


    @Override
    protected RecordProcessor getRecordProcessor(DataRecord record)
    {
        return new RecordWriter(record.getName());
    }


    @Override
    protected RecordProcessor getVectorProcessor(Vector vect)
    {
        return new RecordWriter(vect.getName());
    }


    @Override
    protected ChoiceProcessor getChoiceProcessor(DataChoice choice)
    {
        return new ChoiceWriter(choice.getName());
    }
    
    
    @Override
    protected ArrayProcessor getArrayProcessor(DataArray array)
    {
        return new ArrayWriter(array.getName());
    }
    
    
    @Override
    protected ImplicitSizeProcessor getImplicitSizeProcessor(DataArray array)
    {
        return new ImplicitSizeProcessor();
    }
    
    
    @Override
    protected ArraySizeSupplier getArraySizeSupplier(String refId)
    {
        IntegerWriter sizeWriter = countWriters.get(refId);
        return () -> sizeWriter.val;
    }


}
