/*
 * Copyright 2004-2020 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.pagestore.db;

import static org.h2.util.geometry.GeometryUtils.MAX_X;
import static org.h2.util.geometry.GeometryUtils.MAX_Y;
import static org.h2.util.geometry.GeometryUtils.MIN_X;
import static org.h2.util.geometry.GeometryUtils.MIN_Y;

import java.util.Iterator;

import org.h2.command.query.AllColumnsForPlan;
import org.h2.engine.Session;
import org.h2.index.BaseIndex;
import org.h2.index.Cursor;
import org.h2.index.IndexType;
import org.h2.index.SpatialIndex;
import org.h2.message.DbException;
import org.h2.mvstore.MVStore;
import org.h2.mvstore.db.MVSpatialIndex;
import org.h2.mvstore.rtree.MVRTreeMap;
import org.h2.mvstore.rtree.SpatialKey;
import org.h2.result.Row;
import org.h2.result.SearchRow;
import org.h2.result.SortOrder;
import org.h2.table.IndexColumn;
import org.h2.table.Table;
import org.h2.table.TableFilter;
import org.h2.value.Value;
import org.h2.value.ValueNull;

/**
 * This is an index based on a MVR-TreeMap.
 *
 * @author Thomas Mueller
 * @author Noel Grandin
 * @author Nicolas Fortin, Atelier SIG, IRSTV FR CNRS 24888
 */
public class SpatialTreeIndex extends BaseIndex implements SpatialIndex {

    private static final String MAP_PREFIX  = "RTREE_";

    private final MVRTreeMap<Long> treeMap;
    private final MVStore store;

    private boolean closed;
    private boolean needRebuild;

    /**
     * Constructor.
     *
     * @param table the table instance
     * @param id the index id
     * @param indexName the index name
     * @param columns the indexed columns (only one geometry column allowed)
     * @param persistent whether the index should be persisted
     * @param indexType the index type (only spatial index)
     * @param create whether to create a new index
     * @param session the session.
     */
    public SpatialTreeIndex(Table table, int id, String indexName,
            IndexColumn[] columns, IndexType indexType, boolean persistent,
            boolean create, Session session) {
        super(table, id, indexName, columns, indexType);
        if (indexType.isUnique()) {
            throw DbException.getUnsupportedException("not unique");
        }
        if (!persistent && !create) {
            throw DbException.getUnsupportedException(
                    "Non persistent index called with create==false");
        }
        if (columns.length > 1) {
            throw DbException.getUnsupportedException(
                    "can only do one column");
        }
        if ((columns[0].sortType & SortOrder.DESCENDING) != 0) {
            throw DbException.getUnsupportedException(
                    "cannot do descending");
        }
        if ((columns[0].sortType & SortOrder.NULLS_FIRST) != 0) {
            throw DbException.getUnsupportedException(
                    "cannot do nulls first");
        }
        if ((columns[0].sortType & SortOrder.NULLS_LAST) != 0) {
            throw DbException.getUnsupportedException(
                    "cannot do nulls last");
        }
        this.needRebuild = create;
        if (!database.isStarting()) {
            if (columns[0].column.getType().getValueType() != Value.GEOMETRY) {
                throw DbException.getUnsupportedException(
                        "spatial index on non-geometry column, " +
                        columns[0].column.getCreateSQL());
            }
        }
        if (!persistent) {
            // Index in memory
            store = MVStore.open(null);
            treeMap =  store.openMap("spatialIndex",
                    new MVRTreeMap.Builder<Long>());
        } else {
            if (id < 0) {
                throw DbException.getUnsupportedException(
                        "Persistent index with id<0");
            }
            store = session.getDatabase().getOrCreateStore().getMvStore();
            // Called after CREATE SPATIAL INDEX or
            // by PageStore.addMeta
            treeMap =  store.openMap(MAP_PREFIX + getId(),
                    new MVRTreeMap.Builder<Long>());
            if (treeMap.isEmpty()) {
                needRebuild = true;
            }
        }
    }

    @Override
    public void close(Session session) {
        store.close();
        closed = true;
    }

    @Override
    public void add(Session session, Row row) {
        if (closed) {
            throw DbException.throwInternalError();
        }
        treeMap.add(getKey(row), row.getKey());
    }

    private SpatialKey getKey(SearchRow row) {
        if (row == null) {
            return null;
        }
        Value v = row.getValue(columnIds[0]);
        double[] env;
        if (v == ValueNull.INSTANCE || (env = v.convertToGeometry(null).getEnvelopeNoCopy()) == null) {
            return new SpatialKey(row.getKey());
        }
        return new SpatialKey(row.getKey(),
                (float) env[MIN_X], (float) env[MAX_X], (float) env[MIN_Y], (float) env[MAX_Y]);
    }

    @Override
    public void remove(Session session, Row row) {
        if (closed) {
            throw DbException.throwInternalError();
        }
        if (!treeMap.remove(getKey(row), row.getKey())) {
            throw DbException.throwInternalError("row not found");
        }
    }

    @Override
    public Cursor find(Session session, SearchRow first, SearchRow last) {
        return new SpatialCursor(treeMap.keySet().iterator(), table, session);
    }

    @Override
    public Cursor findByGeometry(Session session, SearchRow first, SearchRow last, SearchRow intersection) {
        if (intersection == null) {
            return find(session, first, last);
        }
        return new SpatialCursor(treeMap.findIntersectingKeys(getKey(intersection)), table, session);
    }

    @Override
    public double getCost(Session session, int[] masks,
            TableFilter[] filters, int filter, SortOrder sortOrder,
            AllColumnsForPlan allColumnsSet) {
        return MVSpatialIndex.getCostRangeIndex(masks, columns);
    }

    @Override
    public void remove(Session session) {
        if (!treeMap.isClosed()) {
            store.removeMap(treeMap);
        }
    }

    @Override
    public void truncate(Session session) {
        treeMap.clear();
    }

    @Override
    public boolean needRebuild() {
        return needRebuild;
    }

    @Override
    public long getRowCount(Session session) {
        return treeMap.sizeAsLong();
    }

    @Override
    public long getRowCountApproximation(Session session) {
        return treeMap.sizeAsLong();
    }

    @Override
    public long getDiskSpaceUsed() {
        // TODO estimate disk space usage
        return 0;
    }

    /**
     * A cursor to iterate over spatial keys.
     */
    private static final class SpatialCursor implements Cursor {

        private final Iterator<SpatialKey> it;
        private SpatialKey current;
        private final Table table;
        private final Session session;

        public SpatialCursor(Iterator<SpatialKey> it, Table table, Session session) {
            this.it = it;
            this.table = table;
            this.session = session;
        }

        @Override
        public Row get() {
            return table.getRow(session, current.getId());
        }

        @Override
        public SearchRow getSearchRow() {
            return get();
        }

        @Override
        public boolean next() {
            if (!it.hasNext()) {
                return false;
            }
            current = it.next();
            return true;
        }

        @Override
        public boolean previous() {
            return false;
        }

    }

}

