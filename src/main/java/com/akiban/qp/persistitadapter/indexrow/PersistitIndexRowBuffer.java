/**
 * END USER LICENSE AGREEMENT (“EULA”)
 *
 * READ THIS AGREEMENT CAREFULLY (date: 9/13/2011):
 * http://www.akiban.com/licensing/20110913
 *
 * BY INSTALLING OR USING ALL OR ANY PORTION OF THE SOFTWARE, YOU ARE ACCEPTING
 * ALL OF THE TERMS AND CONDITIONS OF THIS AGREEMENT. YOU AGREE THAT THIS
 * AGREEMENT IS ENFORCEABLE LIKE ANY WRITTEN AGREEMENT SIGNED BY YOU.
 *
 * IF YOU HAVE PAID A LICENSE FEE FOR USE OF THE SOFTWARE AND DO NOT AGREE TO
 * THESE TERMS, YOU MAY RETURN THE SOFTWARE FOR A FULL REFUND PROVIDED YOU (A) DO
 * NOT USE THE SOFTWARE AND (B) RETURN THE SOFTWARE WITHIN THIRTY (30) DAYS OF
 * YOUR INITIAL PURCHASE.
 *
 * IF YOU WISH TO USE THE SOFTWARE AS AN EMPLOYEE, CONTRACTOR, OR AGENT OF A
 * CORPORATION, PARTNERSHIP OR SIMILAR ENTITY, THEN YOU MUST BE AUTHORIZED TO SIGN
 * FOR AND BIND THE ENTITY IN ORDER TO ACCEPT THE TERMS OF THIS AGREEMENT. THE
 * LICENSES GRANTED UNDER THIS AGREEMENT ARE EXPRESSLY CONDITIONED UPON ACCEPTANCE
 * BY SUCH AUTHORIZED PERSONNEL.
 *
 * IF YOU HAVE ENTERED INTO A SEPARATE WRITTEN LICENSE AGREEMENT WITH AKIBAN FOR
 * USE OF THE SOFTWARE, THE TERMS AND CONDITIONS OF SUCH OTHER AGREEMENT SHALL
 * PREVAIL OVER ANY CONFLICTING TERMS OR CONDITIONS IN THIS AGREEMENT.
 */

package com.akiban.qp.persistitadapter.indexrow;

import com.akiban.ais.model.*;
import com.akiban.qp.expression.BoundExpressions;
import com.akiban.qp.persistitadapter.PersistitAdapter;
import com.akiban.qp.row.IndexRow;
import com.akiban.qp.util.PersistitKey;
import com.akiban.server.PersistitKeyPValueSource;
import com.akiban.server.PersistitKeyValueSource;
import com.akiban.server.collation.AkCollator;
import com.akiban.server.geophile.Space;
import com.akiban.server.rowdata.FieldDef;
import com.akiban.server.rowdata.RowData;
import com.akiban.server.rowdata.RowDataValueSource;
import com.akiban.server.store.PersistitKeyAppender;
import com.akiban.server.types.AkType;
import com.akiban.server.types.ValueSource;
import com.akiban.server.types3.pvalue.PUnderlying;
import com.akiban.util.ArgumentValidation;
import com.persistit.Exchange;
import com.persistit.Key;
import com.persistit.Value;
import com.persistit.exception.PersistitException;

/*
 * 
 * Index row formats:
 * 
 * NON-UNIQUE INDEX:
 * 
 * - Persistit key contains all declared and undeclared (hkey) fields.
 * 
 * PRIMARY KEY INDEX:
 * 
 * - Persistit key contains all declared fields
 * 
 * - Persistit value contains all undeclared fields.
 * 
 * UNIQUE INDEX:
 * 
 * - Persistit key contains all declared fields
 * 
 * - Persistit key also contains one more long field, needed to ensure that insertion of an index row that contains
 *   at least one null and matches a row already in the index (including any nulls) is not considered a duplicate.
 *   For an index row with no nulls, this field contains zero. For a field with nulls, this field contains a value
 *   that is unique within the index. This mechanism is not needed for primary keys because primary keys can only
 *   contain NOT NULL columns.
 * 
 * - Persistit value contains all undeclared fields.
 * 
 */

public class PersistitIndexRowBuffer extends IndexRow
{
    // BoundExpressions interface

    public final int compareTo(BoundExpressions row, int thisStartIndex, int thatStartIndex, int fieldCount)
    {
        // The dependence on field positions and fieldCount is a problem for spatial indexes
        if (index.isSpatial()) {
            throw new UnsupportedOperationException(index.toString());
        }
        // field and byte indexing is as if the pKey and pValue were one contiguous array of bytes. But we switch
        // from pKey to pValue as needed to avoid having to actually copy the bytes into such an array.
        PersistitIndexRowBuffer that = (PersistitIndexRowBuffer) row;
        Key thisKey;
        Key thatKey;
        if (thisStartIndex < this.pKeyFields) {
            thisKey = this.pKey;
        } else {
            thisKey = this.pValue;
            thisStartIndex -= this.pKeyFields;
        }
        if (thatStartIndex < that.pKeyFields) {
            thatKey = that.pKey;
        } else {
            thatKey = that.pValue;
            thatStartIndex -= that.pKeyFields;
        }
        int thisPosition = thisKey.indexTo(thisStartIndex).getIndex();
        int thatPosition = thatKey.indexTo(thatStartIndex).getIndex();
        byte[] thisBytes = thisKey.getEncodedBytes();
        byte[] thatBytes = thatKey.getEncodedBytes();
        int c = 0;
        int eqSegments = 0;
        while (eqSegments < fieldCount) {
            byte thisByte = thisBytes[thisPosition++];
            byte thatByte = thatBytes[thatPosition++];
            c = thisByte - thatByte;
            if (c != 0) {
                break;
            } else if (thisByte == 0) {
                // thisByte = thatByte = 0
                eqSegments++;
                if (thisStartIndex + eqSegments == this.pKeyFields) {
                    thisBytes = this.pValue.getEncodedBytes();
                    thisPosition = 0;
                }
                if (thatStartIndex + eqSegments == that.pKeyFields) {
                    thatBytes = that.pValue.getEncodedBytes();
                    thatPosition = 0;
                }
            }
        }
        // If c == 0 then the two subarrays must match.
        if (c < 0) {
            c = -(eqSegments + 1);
        } else if (c > 0) {
            c = eqSegments + 1;
        }
        return c;
    }

    // IndexRow interface

    @Override
    public void initialize(RowData rowData, Key hKey)
    {
        pKeyAppends = 0;
        int indexField = 0;
        if (spatialHandler != null) {
            spatialHandler.bind(rowData);
            keyAppender().append(spatialHandler.zValue());
            indexField = spatialHandler.dimensions();
        }
        IndexRowComposition indexRowComp = index.indexRowComposition();
        FieldDef[] fieldDefs = index.indexDef().getRowDef().getFieldDefs();
        while (indexField < indexRowComp.getLength()) {
            if (indexRowComp.isInRowData(indexField)) {
                keyAppender().append(fieldDefs[indexRowComp.getFieldPosition(indexField)], rowData);
            } else if (indexRowComp.isInHKey(indexField)) {
                keyAppender().appendFieldFromKey(hKey, indexRowComp.getHKeyPosition(indexField));
            } else {
                throw new IllegalStateException("Invalid IndexRowComposition: " + indexRowComp);
            }
            indexField++;
            pKeyAppends++;
        }
    }

    @Override
    public void append(Column column, ValueSource source)
    {
        // There is no hard requirement that the index is a group index. But while we're adding support for
        // spatial, we just want to be precise about what kind of index is in use.
        assert index.isGroupIndex();
        keyAppender().append(source, column);
        pKeyAppends++;
    }

    @Override
    public void close()
    {
        // Write null-separating value if necessary
        if (index.isUniqueAndMayContainNulls()) {
            long nullSeparator = 0;
            boolean hasNull = false;
            int keyFields = index.getKeyColumns().size();
            for (int f = 0; !hasNull && f < keyFields; f++) {
                pKey.indexTo(f);
                hasNull = pKey.isNull();
            }
            if (hasNull) {
                nullSeparator = index.nextNullSeparatorValue(adapter.persistit().treeService());
            }
            pKeyAppender.append(nullSeparator);
        }
        // If necessary, copy pValue state into value. (Check pValueAppender, because that is non-null only in
        // a writeable PIRB.)
        if (pValueAppender != null) {
            value.clear();
            value.putByteArray(pValue.getEncodedBytes(), 0, pValue.getEncodedSize());
        }
    }

    // PersistitIndexRowBuffer interface

    public void appendFieldTo(int position, PersistitKeyAppender target)
    {
        if (position < pKeyFields) {
            PersistitKey.appendFieldFromKey(target.key(), pKey, position);
        } else {
            PersistitKey.appendFieldFromKey(target.key(), pValue, position - pKeyFields);
        }
        pKeyAppends++;
    }

    public void tableBitmap(long bitmap)
    {
        value.put(bitmap);
    }

    // For table index rows
    public void resetForWrite(Index index, Key key)
    {
        reset(index, key, null, true);
    }

    // For group index rows
    public void resetForWrite(Index index, Key key, Value value)
    {
        reset(index, key, value, true);
    }

    public void resetForRead(Index index, Key key, Value value)
    {
        reset(index, key, value, false);
    }

    public PersistitIndexRowBuffer(PersistitAdapter adapter)
    {
        ArgumentValidation.notNull("adapter", adapter);
        this.adapter = adapter;
    }

    public boolean keyEmpty()
    {
        return pKey.getEncodedSize() == 0;
    }

    // For use by subclasses

    protected void attach(PersistitKeyValueSource source, int position, AkType type, AkCollator collator)
    {
        if (position < pKeyFields) {
            source.attach(pKey, position, type, collator);
        } else {
            source.attach(pValue, position - pKeyFields, type, collator);
        }
    }

    protected void attach(PersistitKeyPValueSource source, int position, PUnderlying type)
    {
        if (index.isSpatial()) {
            throw new UnsupportedOperationException("Spatial indexes don't implement types3 yet");
        }
        if (position < pKeyFields) {
            source.attach(pKey, position, type);
        } else {
            source.attach(pValue, position - pKeyFields, type);
        }
    }

    protected void copyFrom(Exchange exchange) throws PersistitException
    {
        exchange.getKey().copyTo(pKey);
        if (index.isUnique()) {
            byte[] source = exchange.getValue().getByteArray();
            pValue.setEncodedSize(source.length);
            byte[] target = pValue.getEncodedBytes();
            System.arraycopy(source, 0, target, 0, source.length);
        }
    }

    protected void constructHKeyFromIndexKey(Key hKey, IndexToHKey indexToHKey)
    {
        hKey.clear();
        for (int i = 0; i < indexToHKey.getLength(); i++) {
            if (indexToHKey.isOrdinal(i)) {
                hKey.append(indexToHKey.getOrdinal(i));
            } else {
                int indexField = indexToHKey.getIndexRowPosition(i);
                if (index.isSpatial()) {
                    // A spatial index has a single key column (the z-value), representing the declared key columns.
                    indexField = indexField - index.getKeyColumns().size() + 1;
                }
                Key keySource;
                if (indexField < pKeyFields) {
                    keySource = pKey;
                } else {
                    keySource = pValue;
                    indexField -= pKeyFields;
                }
                if (indexField < 0 || indexField > keySource.getDepth()) {
                    throw new IllegalStateException(String.format("keySource: %s, indexField: %s",
                                                                  keySource, indexField));
                }
                PersistitKey.appendFieldFromKey(hKey, keySource, indexField);
            }
        }
    }

    // For use by this class

    private PersistitKeyAppender keyAppender()
    {
        return pKeyAppends < pKeyFields ? pKeyAppender : pValueAppender;
    }

    private void reset(Index index, Key key, Value value, boolean writable)
    {
        assert !index.isUnique() || index.isTableIndex() : index;
        this.index = index;
        this.pKey = key;
        this.pValue = adapter.newKey();
        this.value = value;
        if (index.isSpatial()) {
            this.nIndexFields = index.getAllColumns().size() - index.getKeyColumns().size() + 1;
            this.pKeyFields = this.nIndexFields;
            this.spatialHandler = new SpatialHandler();
        } else {
            this.nIndexFields = index.getAllColumns().size();
            this.pKeyFields = index.isUnique() ? index.getKeyColumns().size() : index.getAllColumns().size();
            this.spatialHandler = null;
        }
        if (writable) {
            this.pKeyAppender = PersistitKeyAppender.create(key);
            this.pKeyAppends = 0;
            this.pValueAppender =
                index.isUnique()
                ? PersistitKeyAppender.create(this.pValue)
                : null;
            if (value != null) {
                value.clear();
            }
        } else {
            if (value != null) {
                value.getByteArray(pValue.getEncodedBytes(), 0, 0, value.getArrayLength());
                pValue.setEncodedSize(value.getArrayLength());
            }
            this.pKeyAppender = null;
            this.pValueAppender = null;
        }
    }

    // Object state

    // The notation involving "keys" and "values" is tricky because this code deals with both the index view and
    // the persistit view, and these don't match up exactly.
    //
    // The index view of keys and values: An application-defined index has a key comprising
    // one or more columns from one table (table index) or multiple tables (group index). An index row has fields
    // corresponding to these columns, and additional fields corresponding to undeclared hkey columns.
    // Index.getKeyColumns refers to the declared columns, and Index.getAllColumns refers to the declared and
    // undeclared columns.
    //
    // The persistit view: A record managed by Persistit has a Key and a Value.
    //
    // The mapping: For a non-unique index, all of an index's columns (declared and undeclared) are stored in
    // the Persistit Key. For a unique index, the declared columns are stored in the Persistit Key while the
    // remaining columns are stored in the Persistit Value. Group indexes are never unique, so all columns
    // are in the Persistit Key and the Persistit Value is used to store the "table bitmap".
    //
    // Terminology: To try and avoid confusion, the terms pKey and pValue will be used when referring to Persistit
    // Keys and Values. The term key will refer to an index key.
    //
    // So why is pValueAppender a PersistitKeyAppender? Because it is convenient to treat index fields
    // in the style of Persistit Key fields. That permits, for example, byte[] comparisons to determine how values
    // that happen to reside in a Persistit Value (i.e., an undeclared field of an index row for a unique index).
    // So as an index row is being created, we deal entirely with Persisitit Keys, via pKeyAppender or pValueAppender.
    // Only when it is time to write the row are the bytes managed by the pValueAppender written as a single
    // Persistit Value.
    protected final PersistitAdapter adapter;
    protected Index index;
    protected int nIndexFields;
    private Key pKey;
    private Key pValue;
    private PersistitKeyAppender pKeyAppender;
    private PersistitKeyAppender pValueAppender;
    private int pKeyFields;
    private Value value;
    private int pKeyAppends = 0;
    private SpatialHandler spatialHandler;

    // Inner classes

    // TODO: types3 version
    private class SpatialHandler
    {
        public int dimensions()
        {
            return dimensions;
        }

        public void bind(RowData rowData)
        {
            for (int d = 0; d < dimensions; d++) {
                rowDataValueSource.bind(fieldDefs[d], rowData);
                switch (types[d]) {
                    case INT:
                        coords[d] = rowDataValueSource.getInt();
                        break;
                    case LONG:
                        coords[d] = rowDataValueSource.getLong();
                        break;
                    // TODO: DECIMAL
                    default:
                        assert false : fieldDefs[d].column();
                        break;
                }
            }
        }

        public long zValue()
        {
            return space.shuffle(coords);
        }

        private Space space;
        private final int dimensions;
        private final AkType[] types;
        private final FieldDef[] fieldDefs;
        private final long[] coords;
        private final RowDataValueSource rowDataValueSource;

        {
            space = ((TableIndex)index).space();
            dimensions = space.dimensions();
            assert index.getKeyColumns().size() == dimensions;
            types = new AkType[dimensions];
            fieldDefs = new FieldDef[dimensions];
            coords = new long[dimensions];
            rowDataValueSource = new RowDataValueSource();
            for (IndexColumn indexColumn : index.getKeyColumns()) {
                int position = indexColumn.getPosition();
                Column column = indexColumn.getColumn();
                types[position] = column.getType().akType();
                fieldDefs[position] = column.getFieldDef();
            }
        }
    }
}