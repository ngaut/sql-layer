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

package com.akiban.ais.ddl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Arrays;

import com.akiban.ais.model.AISBuilder;
import com.akiban.ais.model.AkibanInformationSchema;
import com.akiban.ais.model.Index;
import com.akiban.ais.model.UserTable;
import org.junit.Test;

import com.akiban.ais.model.CharsetAndCollation;
import com.akiban.ais.model.Column;
import com.akiban.ais.model.Table;
import com.akiban.ais.model.TableName;
import com.akiban.server.AkServer;

public class SchemaDefToAisTest {

    private final static String DDL_FILE_NAME = "DDLSourceTest_schema.ddl";
    private final static String SCHEMA_NAME = "DDLSourceTest_schema";

    private final static String[] EXPECTED_CHARSET_VALUES = new String[] { "latin1", "utf8", "utf8", "latin1", "utf8",
        "utf8" };
    
    private final static String[] EXPECTED_COLLATION_VALUES = new String[]{
        "utf8_general_ci", "utf8_general_ci", "utf8_general_ci",
        "latin1_german2_ci", "utf8_general_ci", 
        "utf8_general_ci"};
    
    private AkibanInformationSchema buildAISfromResource(final String resourceName) throws Exception {
        InputStream inputStream = AkServer.class.getClassLoader().getResourceAsStream(resourceName);
        SchemaDef schemaDef = SchemaDef.parseSchemaFromStream(inputStream);
        return new SchemaDefToAis(schemaDef, true).getAis();
    }

    private AkibanInformationSchema buildAISfromString(final String schema, boolean akibandbOnly) throws Exception {
        final SchemaDef schemaDef = SchemaDef.parseSchema(schema);
        return new SchemaDefToAis(schemaDef, akibandbOnly).getAis();
    }

    private AkibanInformationSchema buildAISfromString(final String schema) throws Exception {
        return buildAISfromString(schema, true);
    }


    @Test
    public void testParseEnumAndSet() throws Exception {
        AkibanInformationSchema ais = buildAISfromResource(DDL_FILE_NAME);

        final Table enumTable = ais.getTable(new TableName(SCHEMA_NAME,
                "with_enum"));
        assertEquals(1, enumTable.getColumn("e4").getMaxStorageSize()
                .intValue());
        assertEquals(1, enumTable.getColumn("e255").getMaxStorageSize()
                .intValue());
        assertEquals(2, enumTable.getColumn("e10000").getMaxStorageSize()
                .intValue());

        final Table setTable = ais.getTable(new TableName(SCHEMA_NAME,
                "with_set"));
        assertEquals(1, setTable.getColumn("s8").getMaxStorageSize().intValue());
        assertEquals(2, setTable.getColumn("s16").getMaxStorageSize()
                .intValue());
        assertEquals(3, setTable.getColumn("s24").getMaxStorageSize()
                .intValue());
        assertEquals(4, setTable.getColumn("s32").getMaxStorageSize()
                .intValue());
        assertEquals(8, setTable.getColumn("s64").getMaxStorageSize()
                .intValue());
    }

    @Test
    public void columnMarkedAsPrimaryKey() throws Exception {
        SchemaDef.UserTableDef tableDef = createTableFromInner("id int PRIMARY KEY, whatever int");
        assertEquals("columns", Arrays.asList("id", "whatever"),
                tableDef.getColumnNames());
        assertEquals("PK columns", Arrays.asList("id"),
                tableDef.getPrimaryKey());
        assertEquals("other indexes", 0, tableDef.indexes.size());
    }

    @Test
    public void columnMarkedAsKey() throws Exception {
        SchemaDef.UserTableDef tableDef = createTableFromInner("id int KEY, whatever int");
        assertEquals("columns", Arrays.asList("id", "whatever"),
                tableDef.getColumnNames());
        assertEquals("PK columns", Arrays.asList("id"),
                tableDef.getPrimaryKey());
        assertEquals("other indexes", 0, tableDef.indexes.size());
    }

    @Test
    public void tableHasTwoPKsIndexes() throws Exception {
        createTableWithError(SchemaDef.SchemaDefException.class,
                "too many primary keys",
                "id int, sid int, PRIMARY KEY (id), PRIMARY KEY (sid)");
    }

    @Test
    public void tableHasTwoPrimaryColumns() throws Exception {
        createTableWithError(SchemaDef.SchemaDefException.class,
                "only one column may be marked as [PRIMARY] KEY",
                "id int primary key, sid int primary key");
    }

    @Test
    public void tableMixesPrimaryColumnAndPkIndex() {
        createTableWithError(SchemaDef.SchemaDefException.class,
                "too many primary keys",
                "id int, sid int primary key, primary key(id)");
    }

    @Test
    public void testAkibanFKUnnamedIndex() throws Exception {
        SchemaDef.UserTableDef tableDef = new SchemaDef()
                .parseCreateTable("create table two (id int, oid int, PRIMARY KEY (id), "
                        + "CONSTRAINT `__akiban_fk` FOREIGN KEY (`oid`) REFERENCES zebra (id) ) engine=akibandb;");

        assertEquals("schema", null, tableDef.getCName().getSchema());
        assertEquals("table", "two", tableDef.getCName().getName());

        assertEquals("columns", 2, tableDef.getColumns().size());
        assertEquals("column[0]", "id", tableDef.getColumns().get(0).getName());
        assertEquals("column[1]", "oid", tableDef.getColumns().get(1).getName());

        assertEquals("PK columns", 1, tableDef.primaryKey.size());
        assertEquals("PK[0]", "id", tableDef.primaryKey.get(0));

        assertEquals("indexes", 1, tableDef.indexes.size());
        assertEquals("index[0] name", "__akiban_fk",
                tableDef.indexes.get(0).name);
        assertEquals("index[0] constraints", 1,
                tableDef.indexes.get(0).constraints.size());
        assertEquals("index[0] constraint[0]", "__akiban_fk",
                tableDef.indexes.get(0).constraints.get(0));
        assertEquals("index[0] child columns", 1, tableDef.indexes.get(0)
                .getChildColumns().size());
        assertEquals("index[0] child column[0]", "oid", tableDef.indexes.get(0)
                .getChildColumns().get(0));
        assertEquals("index[0] parent schema", null, tableDef.indexes.get(0)
                .getParentSchema());
        assertEquals("index[0] parent table", "zebra", tableDef.indexes.get(0)
                .getParentTable());
        assertEquals("index[0] parent columns", 1, tableDef.indexes.get(0)
                .getParentColumns().size());
        assertEquals("index[0] parent column[0]", "id", tableDef.indexes.get(0)
                .getParentColumns().get(0));
    }

    private static void createTableWithError(
            Class<? extends Throwable> errClass, String errMessage,
            String innerDDL) {
        Throwable thrown = null;
        try {
            createTableFromInner(innerDDL);
        } catch (Throwable t) {
            thrown = t;
        }
        assertNotNull("expected " + errClass, thrown);
        assertEquals("exception class", errClass, thrown.getClass());
        assertEquals("exception message", errMessage, thrown.getMessage());
    }

    private static SchemaDef.UserTableDef createTableFromInner(String ddl)
            throws Exception {
        SchemaDef schemaDef = new SchemaDef();
        schemaDef.parseCreateTable("create table s.t ("
                + ddl + ") engine=akibandb;");
        SchemaDef.UserTableDef ret = schemaDef.getCurrentTable();
        assertEquals("schema", "s", ret.getCName().getSchema());
        assertEquals("table", "t", ret.getCName().getName());
        assertEquals("engine", "akibandb", ret.engine);
        return ret;
    }

    @Test
    public void testParseCreateTable() throws Exception {
        SchemaDef.UserTableDef tableDef = new SchemaDef()
                .parseCreateTable("create table two (id int, oid int, PRIMARY KEY (id), "
                        + "CONSTRAINT `__akiban_fk` FOREIGN KEY `__akiban_index` (`oid`) REFERENCES zebra (id) ) engine=akibandb;");

        assertEquals("schema", null, tableDef.getCName().getSchema());
        assertEquals("table", "two", tableDef.getCName().getName());

        assertEquals("columns", 2, tableDef.getColumns().size());
        assertEquals("column[0]", "id", tableDef.getColumns().get(0).getName());
        assertEquals("column[1]", "oid", tableDef.getColumns().get(1).getName());

        assertEquals("PK columns", 1, tableDef.primaryKey.size());
        assertEquals("PK[0]", "id", tableDef.primaryKey.get(0));

        assertEquals("indexes", 1, tableDef.indexes.size());
        assertEquals("index[0] name", "__akiban_fk",
                tableDef.indexes.get(0).name);
        assertEquals("index[0] constraints", 1,
                tableDef.indexes.get(0).constraints.size());
        assertEquals("index[0] constraint[0]", "__akiban_fk",
                tableDef.indexes.get(0).constraints.get(0));
        assertEquals("index[0] child columns", 1, tableDef.indexes.get(0)
                .getChildColumns().size());
        assertEquals("index[0] child column[0]", "oid", tableDef.indexes.get(0)
                .getChildColumns().get(0));
        assertEquals("index[0] parent schema", null, tableDef.indexes.get(0)
                .getParentSchema());
        assertEquals("index[0] parent table", "zebra", tableDef.indexes.get(0)
                .getParentTable());
        assertEquals("index[0] parent columns", 1, tableDef.indexes.get(0)
                .getParentColumns().size());
        assertEquals("index[0] parent column[0]", "id", tableDef.indexes.get(0)
                .getParentColumns().get(0));
    }

    public static void main(final String[] args) throws Exception {
        final BufferedReader console = new BufferedReader(
                new InputStreamReader(System.in));
        for (;;) {
            System.out.println();
            System.out.print("Enter a line to parse: ");
            final String line = console.readLine();
            if (line == null) {
                break;
            }
            final SchemaDef schemaDef = new SchemaDef();
            try {
                final SchemaDef.UserTableDef tableDef = schemaDef
                        .parseCreateTable(line);
            } catch (Exception e) {
                e.printStackTrace();
                continue;
            }
            System.out.println("OK");
        }
    }

    @Test
    public void charsetAndCollate() throws Exception {
        AkibanInformationSchema ais = buildAISfromResource(DDL_FILE_NAME);

        final Table utf8Table = ais.getTable(new TableName(SCHEMA_NAME,
                "with_utf8"));
        for (final Column column : utf8Table.getColumns()) {
            final CharsetAndCollation cs = column.getCharsetAndCollation();
            final int columnIndex = column.getPosition();
            assertEquals(EXPECTED_CHARSET_VALUES[columnIndex], cs.charset());
            assertEquals(EXPECTED_COLLATION_VALUES[columnIndex], cs.collation());
        }
    }

    // Invalid data type caused parse failure and invalid AIS
    @Test
    public void bug722121() throws Exception {
        final String schema = "CREATE TABLE test.t(id int NOT NULL, geom geometry NOT NULL, PRIMARY KEY(id), SPATIAL KEY(geom)) engine=akibandb";
        AkibanInformationSchema ais = buildAISfromString(schema);
        assertNotNull(ais);
        final Table table = ais.getUserTable("test", "t");
        assertNotNull(table);
        assertEquals("geometry", table.getColumn("geom").getType().name());
        assertEquals(2, table.getIndexes().size());
    }

    @Test
    public void testOverloadedGroupName() throws Exception {
        String ddl = "CREATE TABLE `s1`.one (idOne int, PRIMARY KEY (idOne)) engine=akibandb;\n"
                   + "CREATE TABLE `s2`.one (idTwo int, PRIMARY KEY (idTwo)) engine=akibandb;";

        AkibanInformationSchema ais = buildAISfromString(ddl);
        assertEquals("user tables", 2, ais.getUserTables().size());
        assertEquals("group tables", 2, ais.getGroupTables().size());
        UserTable s1 = ais.getUserTable("s1", "one");
        UserTable s2 = ais.getUserTable("s2", "one");
        assertNotNull("s1", s1);
        assertNotNull("s2", s2);
        assertSame("s1 group's root", s1, ais.getGroup("one").getGroupTable().getRoot());
        assertSame("s2 group's root", s2, ais.getGroup("one$0").getGroupTable().getRoot());
    }

    @Test
    public void foreignKeysOnNonAkibanTable() throws Exception {
        // Expected behavior: all joins (foreign keys) are preserved
        final String ddl = "create table test.p(id int key) engine=innodb;"+
                           "create table test.x(id int key, pid int, constraint foreign key(pid) references p(id)) engine=innodb;"+
                           "create table test.y(id int key, pid int, xid int, constraint foreign key(pid) references p(id), constraint foreign key(xid) references x(id)) engine=innodb;";
        AkibanInformationSchema ais = buildAISfromString(ddl, false);
        final UserTable p = ais.getUserTable("test", "p");
        assertEquals(0, p.getCandidateParentJoins().size());
        assertEquals(2, p.getCandidateChildJoins().size());
        final UserTable x = ais.getUserTable("test", "x");
        assertEquals(1, x.getCandidateParentJoins().size());
        assertEquals(1, x.getCandidateChildJoins().size());
        final UserTable y = ais.getUserTable("test", "y");
        assertEquals(2, y.getCandidateParentJoins().size());
        assertEquals(0, y.getCandidateChildJoins().size());
    }

    @Test
    public void foreignKeysOnAkibanTable() throws Exception {
        // Expected behavior: A) __akiban joins are preserved with at least one index named __akiban as an 'AKIBAN KEY'
        //                    B) other joins discard but the generated index is kept
        final String ddl = "create table test.p(id int key) engine=akibandb;"+
                           "create table test.x(id int key, pid int, constraint __akiban1 foreign key(pid) references p(id)) engine=akibandb;"+
                           "create table test.y(id int key, pid int, xid int, constraint foreign key(pid) references p(id), constraint __akiban2 foreign key(xid) references x(id)) engine=akibandb;";
        AkibanInformationSchema ais = buildAISfromString(ddl, false);
        final UserTable p = ais.getUserTable("test", "p");
        assertEquals(0, p.getCandidateParentJoins().size());
        assertEquals(1, p.getCandidateChildJoins().size());
        assertEquals(1, p.getChildJoins().size());
        final UserTable x = ais.getUserTable("test", "x");
        assertEquals(1, x.getCandidateParentJoins().size());
        assertNotNull(x.getParentJoin());
        assertEquals(1, x.getCandidateChildJoins().size());
        assertEquals(1, x.getChildJoins().size());
        assertEquals(2, x.getIndexes().size()); // pk, fk
        final Index xIndex = x.getIndex("__akiban1");
        assertNotNull(xIndex);
        assertEquals("FOREIGN KEY", xIndex.getConstraint());
        final UserTable y = ais.getUserTable("test", "y");
        assertEquals(1, y.getCandidateParentJoins().size());
        assertNotNull(y.getParentJoin());
        assertEquals(0, y.getCandidateChildJoins().size());
        assertEquals(3, y.getIndexes().size()); // pk, fk, fk
        final Index yIndex1 = y.getIndex("pid");
        assertNotNull(yIndex1);
        assertEquals("KEY", yIndex1.getConstraint());
        final Index yIndex2 = y.getIndex("__akiban2");
        assertNotNull(yIndex2);
        assertEquals("FOREIGN KEY", yIndex2.getConstraint());
    }

    @Test(expected=AISBuilder.GroupStructureException.class)
    public void tableWithTwoParents() throws Exception {
        // Can parse and construct SchemaDef, but not AIS
        final String ddl = "create table test.p(id int key) engine=akibandb;"+
                           "create table test.x(id int key) engine=akibandb;"+
                           "create table test.y(id int key, pid int, xid int, constraint __akiban1 foreign key(pid) references p(id), constraint __akiban2 foreign key(xid) references x(id)) engine=akibandb;";
        final SchemaDef schemaDef = SchemaDef.parseSchema(ddl);
        final SchemaDef.UserTableDef yTable = schemaDef.getUserTableMap().get(new SchemaDef.CName("test","y"));
        assertEquals(2, yTable.getAkibanJoinIndexes().size());
        new SchemaDefToAis(schemaDef, true).getAis();
    }
}
