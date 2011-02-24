/**
 * Copyright (C) 2011 Akiban Technologies Inc.
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses.
 */

package com.akiban.server.api.dml.scan;

import com.akiban.server.InvalidOperationException;
import com.akiban.server.RowData;
import com.akiban.server.api.DMLFunctions;
import com.akiban.server.service.session.Session;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class RowDataOutput implements LegacyRowOutput {
    private final List<RowData> rowDatas = new ArrayList<RowData>();

    public List<RowData> getRowDatas() {
        return rowDatas;
    }

    @Override
    public ByteBuffer getOutputBuffer() throws RowOutputException {
        return null;
    }

    @Override
    public void wroteRow() throws RowOutputException {
        throw new UnsupportedOperationException("Shouldn't be calling wroteRow for output to an HAPI request");
    }

    @Override
    public void addRow(RowData rowData)
    {
        rowDatas.add(rowData);
    }

    @Override
    public int getRowsCount() {
        return rowDatas.size();
    }

    /**
     * Convenience method for doing a full scan (that is, a scan until there are no more rows to be scanned for the
     * request) and returning the rows.
     *
     *
     * @param session the session in which to scan
     * @param dml the DMLFunctions to handle the scan
     * @param request the scan request
     * @return the resulting rows
     * @throws InvalidOperationException if thrown at any point during the scan
     * @throws BufferFullException if the given buffer isn't big enough for the required data
     */
    public static List<RowData> scanFull(Session session, DMLFunctions dml, ScanRequest request)
            throws InvalidOperationException, BufferFullException
    {
        final RowDataOutput output = new RowDataOutput();
        request.setOutputToMessage(false);
        CursorId scanCursor = dml.openCursor(session, request);
        try {
            while(dml.scanSome(session, scanCursor, output, -1))
            {}
            return output.getRowDatas();
        } finally {
            dml.closeCursor(session, scanCursor);
        }
    }
}
