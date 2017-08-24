package io.mycat.sqlengine.mpp;

import io.mycat.MycatServer;
import io.mycat.backend.mysql.BufferUtil;
import io.mycat.backend.mysql.MySQLMessage;
import io.mycat.backend.mysql.nio.handler.MultiNodeQueryHandler;
import io.mycat.memory.MyCatMemory;
import io.mycat.memory.unsafe.memory.mm.DataNodeMemoryManager;
import io.mycat.memory.unsafe.memory.mm.MemoryManager;
import io.mycat.memory.unsafe.row.BufferHolder;
import io.mycat.memory.unsafe.row.StructType;
import io.mycat.memory.unsafe.row.UnsafeRow;
import io.mycat.memory.unsafe.row.UnsafeRowWriter;
import io.mycat.memory.unsafe.utils.MycatPropertyConf;
import io.mycat.memory.unsafe.utils.sort.PrefixComparator;
import io.mycat.memory.unsafe.utils.sort.PrefixComparators;
import io.mycat.memory.unsafe.utils.sort.RowPrefixComputer;
import io.mycat.memory.unsafe.utils.sort.UnsafeExternalRowSorter;
import io.mycat.net.mysql.EOFPacket;
import io.mycat.net.mysql.RowDataPacket;
import io.mycat.route.RouteResultset;
import io.mycat.server.ServerConnection;
import io.mycat.util.StringUtil;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;


/**
 * Created by zagnix on 2016/6/21.
 */
public class DataNodeMergeManager extends AbstractDataNodeMerge {

    private static final Logger LOGGER = Logger.getLogger(DataNodeMergeManager.class);


    /**
     * 全局sorter，排序器
     */
    private UnsafeExternalRowSorter globalSorter = null;
    /**
     * UnsafeRowGrouper
     */
    private UnsafeRowGrouper unsafeRowGrouper = null;

    /**
     * 全局merge，排序器
     */
    private UnsafeExternalRowSorter globalMergeResult = null;

    /**
     * sorter需要的上下文环境
     */
    private final MyCatMemory serverMemory;
    private final MemoryManager memoryManager;
    private final MycatPropertyConf conf;


    public DataNodeMergeManager(MultiNodeQueryHandler handler, RouteResultset rrs) {
        super(handler, rrs);
        this.serverMemory = MycatServer.getInstance().getServerMemory();
        this.memoryManager = serverMemory.getResultMergeMemoryManager();
        this.conf = serverMemory.getConf();
    }


    public void onRowMetaData(Map<String, ColMeta> columToIndx, int fieldCount) throws IOException {

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("field metadata keys:" + columToIndx.keySet());
            LOGGER.debug("field metadata values:" + columToIndx.values());
        }

        OrderCol[] orderCols = null;
        StructType schema = null;
        UnsafeExternalRowSorter.PrefixComputer prefixComputer = null;
        PrefixComparator prefixComparator = null;


        DataNodeMemoryManager dataNodeMemoryManager = null;

        this.fieldCount = fieldCount;

        if (rrs.getHavingCols() != null) {
            ColMeta colMeta = columToIndx.get(rrs.getHavingCols().getLeft().toUpperCase());

            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("getHavingCols:" + rrs.getHavingCols().toString());
            }
            if (colMeta != null) {
                rrs.getHavingCols().setColMeta(colMeta);
            }
        }

        if (rrs.isHasAggrColumn()) {
            List<MergeCol> mergCols = new LinkedList<MergeCol>();
            Map<String, Integer> mergeColsMap = rrs.getMergeCols();

            if (mergeColsMap != null) {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("isHasAggrColumn:" + mergeColsMap.toString());
                }
                for (Map.Entry<String, Integer> mergEntry : mergeColsMap.entrySet()) {
                    String colName = mergEntry.getKey().toUpperCase();
                    int type = mergEntry.getValue();
                    if (MergeCol.MERGE_AVG == type) {
                        ColMeta sumColMeta = columToIndx.get(colName + "SUM");
                        ColMeta countColMeta = columToIndx.get(colName + "COUNT");
                        if (sumColMeta != null && countColMeta != null) {
                            ColMeta colMeta = new ColMeta(sumColMeta.colIndex,
                                    countColMeta.colIndex,
                                    sumColMeta.getColType());
                            mergCols.add(new MergeCol(colMeta, mergEntry.getValue()));
                        }
                    } else {
                        ColMeta colMeta = columToIndx.get(colName);
                        mergCols.add(new MergeCol(colMeta, mergEntry.getValue()));
                    }
                }
            }

            // add no alias merg column
            for (Map.Entry<String, ColMeta> fieldEntry : columToIndx.entrySet()) {
                String colName = fieldEntry.getKey();
                int result = MergeCol.tryParseAggCol(colName);
                if (result != MergeCol.MERGE_UNSUPPORT && result != MergeCol.MERGE_NOMERGE) {
                    mergCols.add(new MergeCol(fieldEntry.getValue(), result));
                }
            }

            /**
             * Group操作
             */
            unsafeRowGrouper = new UnsafeRowGrouper(columToIndx, rrs.getGroupByCols(),
                    mergCols.toArray(new MergeCol[mergCols.size()]),
                    rrs.getHavingCols());
        }


        if (rrs.getOrderByCols() != null) {
            LinkedHashMap<String, Integer> orders = rrs.getOrderByCols();
            orderCols = new OrderCol[orders.size()];
            int i = 0;
            for (Map.Entry<String, Integer> entry : orders.entrySet()) {
                String key = StringUtil.removeBackQuote(entry.getKey().toUpperCase());
                ColMeta colMeta = columToIndx.get(key);
                if (colMeta == null) {
                    throw new IllegalArgumentException(
                            "all columns in order by clause should be in the selected column list!" + entry.getKey());
                }
                orderCols[i++] = new OrderCol(colMeta, entry.getValue());
            }

            /**
             * 构造全局排序器
             */
            schema = new StructType(columToIndx, fieldCount);
            schema.setOrderCols(orderCols);

            prefixComputer = new RowPrefixComputer(schema);

            prefixComparator = getPrefixComparator(orderCols);

            dataNodeMemoryManager =
                    new DataNodeMemoryManager(memoryManager, Thread.currentThread().getId());

            /**
             * 默认排序，只是将数据连续存储到内存中即可。
             */
            globalSorter = new UnsafeExternalRowSorter(
                    dataNodeMemoryManager,
                    serverMemory,
                    schema,
                    prefixComparator, prefixComputer,
                    conf.getSizeAsBytes("server.buffer.pageSize", "1m"),
                    false/**是否使用基数排序*/,
                    true/**排序*/);
        }


        /**
         * 1.schema
         */

        schema = new StructType(columToIndx, fieldCount);
        schema.setOrderCols(orderCols);

        /**
         * 2 .PrefixComputer
         */
        prefixComputer = new RowPrefixComputer(schema);

        /**
         * 3 .PrefixComparator 默认是ASC，可以选择DESC
         */

        prefixComparator = PrefixComparators.LONG;


        dataNodeMemoryManager = new DataNodeMemoryManager(memoryManager,
                Thread.currentThread().getId());

        globalMergeResult = new UnsafeExternalRowSorter(
                dataNodeMemoryManager,
                serverMemory,
                schema,
                prefixComparator,
                prefixComputer,
                conf.getSizeAsBytes("server.buffer.pageSize", "1m"),
                false, /**是否使用基数排序*/
                false /**不排序*/);
    }

    private PrefixComparator getPrefixComparator(OrderCol[] orderCols) {
        PrefixComparator prefixComparator = null;
        OrderCol firstOrderCol = orderCols[0];
        int orderType = firstOrderCol.getOrderType();
        int colType = firstOrderCol.colMeta.colType;

        switch (colType) {
            case ColMeta.COL_TYPE_INT:
            case ColMeta.COL_TYPE_LONG:
            case ColMeta.COL_TYPE_INT24:
            case ColMeta.COL_TYPE_SHORT:
            case ColMeta.COL_TYPE_LONGLONG:
                prefixComparator = (orderType == OrderCol.COL_ORDER_TYPE_ASC ?
                        PrefixComparators.LONG : PrefixComparators.LONG_DESC);
                break;
            case ColMeta.COL_TYPE_FLOAT:
            case ColMeta.COL_TYPE_DOUBLE:
            case ColMeta.COL_TYPE_DECIMAL:
            case ColMeta.COL_TYPE_NEWDECIMAL:
                prefixComparator = (orderType == OrderCol.COL_ORDER_TYPE_ASC ?
                        PrefixComparators.DOUBLE : PrefixComparators.DOUBLE_DESC);
                break;
            case ColMeta.COL_TYPE_DATE:
            case ColMeta.COL_TYPE_TIMSTAMP:
            case ColMeta.COL_TYPE_TIME:
            case ColMeta.COL_TYPE_YEAR:
            case ColMeta.COL_TYPE_DATETIME:
            case ColMeta.COL_TYPE_NEWDATE:
            case ColMeta.COL_TYPE_BIT:
            case ColMeta.COL_TYPE_VAR_STRING:
            case ColMeta.COL_TYPE_STRING:
            case ColMeta.COL_TYPE_ENUM:
            case ColMeta.COL_TYPE_SET:
                prefixComparator = (orderType == OrderCol.COL_ORDER_TYPE_ASC ?
                        PrefixComparators.BINARY : PrefixComparators.BINARY_DESC);
                break;
            default:
                prefixComparator = (orderType == OrderCol.COL_ORDER_TYPE_ASC ?
                        PrefixComparators.LONG : PrefixComparators.LONG_DESC);
                break;
        }

        return prefixComparator;
    }

    @Override
    public List<RowDataPacket> getResults(byte[] eof) {
        return null;
    }

    @Override
    public void run() {

        if (!running.compareAndSet(false, true)) {
            return;
        }

        boolean nulpack = false;

        try {
            for (; ; ) {
                final PackWraper pack = packs.poll();

                if (pack == null) {
                    nulpack = true;
                    break;
                }
                if (pack == endFlagPack) {
                    /**
                     * 最后一个节点datenode发送了row eof packet说明了整个
                     * 分片数据全部接收完成，进而将结果集全部发给你Mycat 客户端
                     */
                    final int warningCount = 0;
                    final EOFPacket eofp = new EOFPacket();
                    final ByteBuffer eof = ByteBuffer.allocate(9);
                    BufferUtil.writeUB3(eof, eofp.calcPacketSize());
                    eof.put(eofp.packetId);
                    eof.put(eofp.fieldCount);
                    BufferUtil.writeUB2(eof, warningCount);
                    BufferUtil.writeUB2(eof, eofp.status);
                    final ServerConnection source = multiQueryHandler.getSession().getSource();
                    final byte[] array = eof.array();


                    Iterator<UnsafeRow> iters = null;


                    if (unsafeRowGrouper != null) {
                        /**
                         * group by里面需要排序情况
                         */
                        if (globalSorter != null) {
                            iters = unsafeRowGrouper.getResult(globalSorter);
                        } else {
                            iters = unsafeRowGrouper.getResult(globalMergeResult);
                        }

                    } else if (globalSorter != null) {

                        iters = globalSorter.sort();

                    } else {

                        iters = globalMergeResult.sort();

                    }

                    if (iters != null)
                        multiQueryHandler.outputMergeResult(source, array, iters);


                    if (unsafeRowGrouper != null) {
                        unsafeRowGrouper.free();
                        unsafeRowGrouper = null;
                    }

                    if (globalSorter != null) {
                        globalSorter.cleanupResources();
                        globalSorter = null;
                    }

                    if (globalMergeResult != null) {
                        globalMergeResult.cleanupResources();
                        globalMergeResult = null;
                    }

                    break;
                }

                UnsafeRow unsafeRow = new UnsafeRow(fieldCount);
                BufferHolder bufferHolder = new BufferHolder(unsafeRow, 0);
                UnsafeRowWriter unsafeRowWriter = new UnsafeRowWriter(bufferHolder, fieldCount);
                bufferHolder.reset();

                /**
                 *构造一行row，将对应的col填充.
                 */
                MySQLMessage mm = new MySQLMessage(pack.rowData);
                mm.readUB3();
                mm.read();

                for (int i = 0; i < fieldCount; i++) {
                    byte[] colValue = mm.readBytesWithLength();
                    if (colValue != null)
                        unsafeRowWriter.write(i, colValue);
                    else
                        unsafeRow.setNullAt(i);
                }

                unsafeRow.setTotalSize(bufferHolder.totalSize());

                if (unsafeRowGrouper != null) {
                    unsafeRowGrouper.addRow(unsafeRow);
                } else if (globalSorter != null) {
                    globalSorter.insertRow(unsafeRow);
                } else {
                    globalMergeResult.insertRow(unsafeRow);
                }

                unsafeRow = null;
                bufferHolder = null;
                unsafeRowWriter = null;
            }

        } catch (final Exception e) {
            multiQueryHandler.handleDataProcessException(e);
        } finally {
            running.set(false);
            if (nulpack && !packs.isEmpty()) {
                this.run();
            }
        }
    }

    /**
     * 释放DataNodeMergeManager所申请的资源
     */
    public void clear() {

        if (unsafeRowGrouper != null) {
            unsafeRowGrouper.free();
            unsafeRowGrouper = null;
        }

        if (globalSorter != null) {
            globalSorter.cleanupResources();
            globalSorter = null;
        }

        if (globalMergeResult != null) {
            globalMergeResult.cleanupResources();
            globalMergeResult = null;
        }
    }
}
