/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2022 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.api.common;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;

import net.opengis.swe.v20.DataComponent;
import org.sensorhub.utils.VarInt;
import org.vast.util.Asserts;
import com.google.common.collect.ImmutableList;
import com.google.common.io.BaseEncoding;
import com.google.common.primitives.UnsignedBytes;


/**
 * <p>
 * Class used to store arbitrary long IDs (i.e. as many bytes as necessary
 * can be encoded, much like BigInteger), along with a scope.
 * </p><p>
 * The local ID part is typically used as the database local ID while the combination
 * of scope and ID is used as a public ID (for example by the federated database).
 * The scope must be unique for Id provider in order to be able to form a
 * globally unique ID across providers (e.g. different databases).
 * </p>
 *
 * @author Alex Robin
 * @since Apr 14, 2022
 */
public interface BigId extends Comparable<BigId>
{
    public static final BigId NONE = new BigIdZero();
    public static final String NO_LONG_REPRESENTATION = "ID cannot be represented as a long";
    
    static final Comparator<byte[]> BYTES_COMPARATOR = UnsignedBytes.lexicographicalComparator();
    static final BaseEncoding BASE32_ENCODING = BaseEncoding.base32Hex().lowerCase().omitPadding();
    
    
    /**
     * @return The scope within which the ID is valid
     */
    public int getScope();
    
    
    /**
     * @return The value of the ID part as a byte array
     */
    public byte[] getIdAsBytes();
    
    
    /**
     * @return The value of the ID part as a long
     * @throws IllegalArgumentException if this type of BigId cannot be
     * represented by a single long value
     */
    public default long getIdAsLong()
    {
        throw new IllegalArgumentException(NO_LONG_REPRESENTATION);
    }
    
    
    /**
     * @return Size of the ID part in bytes
     */
    public default int size()
    {
        return getIdAsBytes().length;
    }


    @Override
    public default int compareTo(BigId other)
    {
        return compare(this, other);
    }
    
    
    @Override
    public boolean equals(Object other);
    
    
    @Override
    public int hashCode();
    
    
    /**
     * Creates a BigId from a scope and an ID part coded as a byte array
     * @param scope Integer ID scope (e.g. database number)
     * @param id Local ID part
     * @return The new immutable BigId instance
     */
    public static BigId fromBytes(int scope, byte[] id)
    {
        return new BigIdBytes(scope, id);
    }
    
    
    /**
     * Creates a BigId with the given scope and reading the ID part from
     * a portion of a byte array
     * @param scope Integer ID scope (e.g. database number)
     * @param buf Byte array containing the local ID part
     * @param offset Offset where to start reading the ID in the byte array
     * @param len Number of bytes to read from the byte array
     * @return The new immutable BigId instance
     */
    public static BigId fromBytes(int scope, byte[] buf, int offset, int len)
    {
        var id = new byte[len];
        System.arraycopy(buf, offset, id, 0, len);
        return new BigIdBytes(scope, id);
    }
    
    
    /**
     * Creates a BigId from a scope and an ID part coded as a long
     * @param scope Integer ID scope (e.g. database number)
     * @param id Local ID part
     * @return The new immutable BigId instance
     */
    public static BigId fromLong(int scope, long id)
    {
        return new BigIdLong(scope, id);
    }
    
    
    /**
     * Creates multiple BigIds from a scope and their ID parts coded as longs
     * @param scope Integer ID scope (e.g. database number)
     * @param ids One or more local IDs
     * @return A collection of immutable BigId instances
     */
    public static Collection<BigId> fromLongs(int scope, long... ids)
    {
        var builder = ImmutableList.<BigId>builder();
        for (var id: ids)
            builder.add(BigId.fromLong(scope, id));
        return builder.build();
    }
    
    
    public static boolean equals(BigId a, BigId b)
    {
        return a.getScope() == b.getScope() &&
               Arrays.equals(a.getIdAsBytes(), b.getIdAsBytes());
    }
    
    
    /**
     * Compare two BigIds using their byte representations.
     * This function works with all BigId types since they are all required
     * to provide a byte[] representation.
     * @param a First id to compare (must not be null)
     * @param b Second id to compare (must not be null)
     * @return A negative, zero, or positive integer as the first argument is less than, equal to,
     * or greater than the second.
     * @throws NullPointerException if one of the argument is null
     */
    public static int compare(BigId a, BigId b)
    {
        var comp = Integer.compare(a.getScope(), b.getScope());
        if (comp == 0)
           comp = BYTES_COMPARATOR.compare(a.getIdAsBytes(), b.getIdAsBytes());
        return comp;
    }
    
    
    /**
     * Compare two BigIds using their long representations.
     * This will only work for BigId types with an ID part that can be represented
     * as a long.
     * @param a First id to compare (must not be null)
     * @param b Second id to compare (must not be null)
     * @return A negative, zero, or positive integer as the first argument is less than, equal to,
     * or greater than the second.
     * @throws NullPointerException if one of the argument is null
     */
    public static int compareLongs(BigId a, BigId b)
    {
        var comp = Integer.compare(a.getScope(), b.getScope());
        if (comp == 0)
           comp = Long.compare(a.getIdAsLong(), b.getIdAsLong());
        return comp;
    }
    
    
    /**
     * Parse a BigId from a base32 encoded string
     * @param s
     * @return A BigId instance
     * @throws IllegalArgumentException if the provided string is not a valid base32 string
     * as per <a href="https://datatracker.ietf.org/doc/html/rfc4648#section-7">IETF RFC 4648 section 7</a>
     */
    public static BigId fromString32(String s)
    {
        Asserts.checkNotNull(s, String.class);
        
        try
        {
            var reader = new StringReader(s);
            var is = BASE32_ENCODING.decodingStream(reader);
            var scope = VarInt.getVarInt(is);
            var id = is.readAllBytes();
            return BigId.fromBytes(scope, id);
        }
        catch (Exception e)
        {
            throw new IllegalArgumentException("Error decoding BigId: " + s, e);
        }
    }
    
    
    public static String toString32(BigId id)
    {
        Asserts.checkNotNull(id, BigId.class);
        
        try
        {
            var writer = new StringWriter();
            var os = BASE32_ENCODING.encodingStream(writer);
            VarInt.putVarInt(id.getScope(), os);
            os.write(id.getIdAsBytes());
            os.close();
            return writer.toString();
        }
        catch (Exception e)
        {
            throw new IllegalStateException("Error encoding BigId: " + id, e);
        }
    }

}
