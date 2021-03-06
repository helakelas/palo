// Copyright (c) 2017, Baidu.com, Inc. All Rights Reserved

// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package com.baidu.palo.planner;

import com.baidu.palo.analysis.Analyzer;
import com.baidu.palo.analysis.BinaryPredicate;
import com.baidu.palo.analysis.BrokerDesc;
import com.baidu.palo.analysis.CastExpr;
import com.baidu.palo.analysis.Expr;
import com.baidu.palo.analysis.ExprSubstitutionMap;
import com.baidu.palo.analysis.FunctionCallExpr;
import com.baidu.palo.analysis.FunctionName;
import com.baidu.palo.analysis.FunctionParams;
import com.baidu.palo.analysis.NullLiteral;
import com.baidu.palo.analysis.SlotDescriptor;
import com.baidu.palo.analysis.SlotRef;
import com.baidu.palo.analysis.StringLiteral;
import com.baidu.palo.analysis.TupleDescriptor;
import com.baidu.palo.catalog.BrokerMgr;
import com.baidu.palo.catalog.BrokerTable;
import com.baidu.palo.catalog.Catalog;
import com.baidu.palo.catalog.Column;
import com.baidu.palo.catalog.OlapTable;
import com.baidu.palo.catalog.PrimitiveType;
import com.baidu.palo.catalog.ScalarType;
import com.baidu.palo.catalog.Table;
import com.baidu.palo.catalog.Type;
import com.baidu.palo.common.AnalysisException;
import com.baidu.palo.common.ClientPool;
import com.baidu.palo.common.Config;
import com.baidu.palo.common.InternalException;
import com.baidu.palo.load.BrokerFileGroup;
import com.baidu.palo.service.FrontendOptions;
import com.baidu.palo.system.Backend;
import com.baidu.palo.thrift.TBrokerFileStatus;
import com.baidu.palo.thrift.TBrokerListPathRequest;
import com.baidu.palo.thrift.TBrokerListResponse;
import com.baidu.palo.thrift.TBrokerOperationStatusCode;
import com.baidu.palo.thrift.TBrokerRangeDesc;
import com.baidu.palo.thrift.TBrokerScanNode;
import com.baidu.palo.thrift.TBrokerScanRange;
import com.baidu.palo.thrift.TBrokerScanRangeParams;
import com.baidu.palo.thrift.TBrokerVersion;
import com.baidu.palo.thrift.TExplainLevel;
import com.baidu.palo.thrift.TFileFormatType;
import com.baidu.palo.thrift.TFileType;
import com.baidu.palo.thrift.TNetworkAddress;
import com.baidu.palo.thrift.TPaloBrokerService;
import com.baidu.palo.thrift.TPlanNode;
import com.baidu.palo.thrift.TPlanNodeType;
import com.baidu.palo.thrift.TScanRange;
import com.baidu.palo.thrift.TScanRangeLocation;
import com.baidu.palo.thrift.TScanRangeLocations;

import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.thrift.TException;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;

// Broker scan node
public class BrokerScanNode extends ScanNode {
    private static final Logger LOG = LogManager.getLogger(BrokerScanNode.class);
    private static final TBrokerFileStatusComparator T_BROKER_FILE_STATUS_COMPARATOR
            = new TBrokerFileStatusComparator();

    public static class TBrokerFileStatusComparator implements Comparator<TBrokerFileStatus> {
        @Override
        public int compare(TBrokerFileStatus o1, TBrokerFileStatus o2) {
            if (o1.size < o2.size) {
                return -1;
            } else if (o1.size > o2.size) {
                return 1;
            }
            return 0;
        }
    }

    private final Random random = new Random(System.currentTimeMillis());

    // File groups need to
    private List<TScanRangeLocations> locationsList;

    // used both for load statement and select statement
    private long totalBytes;
    private int numInstances;
    private long bytesPerInstance;

    // Parameters need to process
    private Table targetTable;
    private BrokerDesc brokerDesc;
    private List<BrokerFileGroup> fileGroups;

    private ArrayList<ArrayList<TBrokerFileStatus>> fileStatusesList;

    // Only used for external table in select statement
    private ArrayList<Backend> backends;
    private int nextBe = 0;

    private Analyzer analyzer;

    private List<DataSplitSink.EtlRangePartitionInfo> partitionInfos;
    private List<Expr> partitionExprs;

    private static class ParamCreateContext {
        public BrokerFileGroup fileGroup;
        public TBrokerScanRangeParams params;
        public TupleDescriptor tupleDescriptor;
        public Map<String, Expr> exprMap;
        public Map<String, SlotDescriptor> slotDescByName;
    }

    private List<ParamCreateContext> paramCreateContexts;

    public BrokerScanNode(PlanNodeId id, TupleDescriptor desc, String planNodeName) {
        super(id, desc, planNodeName);
    }

    @Override
    public void init(Analyzer analyzer) throws InternalException {
        super.init(analyzer);

        this.analyzer = analyzer;
        if (desc.getTable() != null) {
            BrokerTable brokerTable = (BrokerTable) desc.getTable();
            try {
                fileGroups = Lists.newArrayList(new BrokerFileGroup(brokerTable));
            } catch (AnalysisException e) {
                throw new InternalException(e.getMessage());
            }
            brokerDesc = new BrokerDesc(brokerTable.getBrokerName(), brokerTable.getBrokerProperties());
            targetTable = brokerTable;
        }

        // Get all broker file status
        assignBackends();
        getAllBrokerFileStatus();

        paramCreateContexts = Lists.newArrayList();
        for (BrokerFileGroup fileGroup : fileGroups) {
            ParamCreateContext context = new ParamCreateContext();
            context.fileGroup = fileGroup;
            try {
                initParams(context);
            } catch (AnalysisException e) {
                throw new InternalException(e.getMessage());
            }
            paramCreateContexts.add(context);
        }
    }

    private boolean isLoad() {
        return desc.getTable() == null;
    }

    public void setLoadInfo(Table targetTable,
                            BrokerDesc brokerDesc,
                            List<BrokerFileGroup> fileGroups) {
        this.targetTable = targetTable;
        this.brokerDesc = brokerDesc;
        this.fileGroups = fileGroups;
    }

    private void createPartitionInfos() throws AnalysisException {
        if (partitionInfos != null) {
            return;
        }

        Map<String, Expr> exprByName = Maps.newHashMap();

        for (SlotDescriptor slotDesc : desc.getSlots()) {
            exprByName.put(slotDesc.getColumn().getName(), new SlotRef(slotDesc));
        }

        partitionExprs = Lists.newArrayList();
        partitionInfos = DataSplitSink.EtlRangePartitionInfo.createParts(
                (OlapTable) targetTable, exprByName, null, partitionExprs);
    }

    private void parseExprMap(Map<String, Expr> exprMap) throws InternalException {
        if (exprMap == null) {
            return;
        }
        for (Map.Entry<String, Expr> entry : exprMap.entrySet()) {
            String colName = entry.getKey();
            Expr originExpr = entry.getValue();

            Column column = targetTable.getColumn(colName);
            if (column == null) {
                throw new InternalException("Unknown column(" + colName + ")");
            }

            // To compatible with older load version
            if (originExpr instanceof FunctionCallExpr) {
                FunctionCallExpr funcExpr = (FunctionCallExpr) originExpr;
                String funcName = funcExpr.getFnName().getFunction();

                if (funcName.equalsIgnoreCase("replace_value")) {
                    List<Expr> exprs = Lists.newArrayList();
                    SlotRef slotRef = new SlotRef(null, entry.getKey());
                    // We will convert this to IF(`col` != child0, `col`, child1),
                    // because we need the if return type equal to `col`, we use NE
                    //
                    exprs.add(new BinaryPredicate(BinaryPredicate.Operator.NE, slotRef, funcExpr.getChild(0)));
                    exprs.add(slotRef);
                    if (funcExpr.hasChild(1)) {
                        exprs.add(funcExpr.getChild(1));
                    } else {
                        if (column.getDefaultValue() != null) {
                            exprs.add(new StringLiteral(column.getDefaultValue()));
                        } else {
                            if (column.isAllowNull()) {
                                exprs.add(NullLiteral.create(Type.VARCHAR));
                            } else {
                                throw new InternalException("Column(" + colName + ") has no default value.");
                            }
                        }
                    }
                    FunctionCallExpr newFn = new FunctionCallExpr("if", exprs);
                    entry.setValue(newFn);
                } else if (funcName.equalsIgnoreCase("strftime")) {
                    FunctionName fromUnixName = new FunctionName("FROM_UNIXTIME");
                    List<Expr> fromUnixArgs = Lists.newArrayList(funcExpr.getChild(1));
                    FunctionCallExpr fromUnixFunc = new FunctionCallExpr(
                            fromUnixName, new FunctionParams(false, fromUnixArgs));

                    entry.setValue(fromUnixFunc);
                } else if (funcName.equalsIgnoreCase("time_format")) {
                    FunctionName strToDateName = new FunctionName("STR_TO_DATE");
                    List<Expr> strToDateExprs = Lists.newArrayList(funcExpr.getChild(2), funcExpr.getChild(1));
                    FunctionCallExpr strToDateFuncExpr = new FunctionCallExpr(
                            strToDateName, new FunctionParams(false, strToDateExprs));

                    FunctionName dateFormatName = new FunctionName("DATE_FORMAT");
                    List<Expr> dateFormatArgs = Lists.newArrayList(strToDateFuncExpr, funcExpr.getChild(0));
                    FunctionCallExpr dateFormatFunc = new FunctionCallExpr(
                            dateFormatName, new FunctionParams(false, dateFormatArgs));

                    entry.setValue(dateFormatFunc);
                } else if (funcName.equalsIgnoreCase("alignment_timestamp")) {
                    FunctionName fromUnixName = new FunctionName("FROM_UNIXTIME");
                    List<Expr> fromUnixArgs = Lists.newArrayList(funcExpr.getChild(1));
                    FunctionCallExpr fromUnixFunc = new FunctionCallExpr(
                            fromUnixName, new FunctionParams(false, fromUnixArgs));

                    StringLiteral precision = (StringLiteral) funcExpr.getChild(0);
                    StringLiteral format;
                    if (precision.getStringValue().equalsIgnoreCase("year")) {
                        format = new StringLiteral("%Y-01-01 00:00:00");
                    } else if (precision.getStringValue().equalsIgnoreCase("month")) {
                        format = new StringLiteral("%Y-%m-01 00:00:00");
                    } else if (precision.getStringValue().equalsIgnoreCase("day")) {
                        format = new StringLiteral("%Y-%m-%d 00:00:00");
                    } else if (precision.getStringValue().equalsIgnoreCase("hour")) {
                        format = new StringLiteral("%Y-%m-%d %H:00:00");
                    } else {
                        throw new InternalException("Unknown precision(" + precision.getStringValue() + ")");
                    }
                    FunctionName dateFormatName = new FunctionName("DATE_FORMAT");
                    List<Expr> dateFormatArgs = Lists.newArrayList(fromUnixFunc, format);
                    FunctionCallExpr dateFormatFunc = new FunctionCallExpr(
                            dateFormatName, new FunctionParams(false, dateFormatArgs));

                    FunctionName unixTimeName = new FunctionName("UNIX_TIMESTAMP");
                    List<Expr> unixTimeArgs = Lists.newArrayList();
                    unixTimeArgs.add(dateFormatFunc);
                    FunctionCallExpr unixTimeFunc = new FunctionCallExpr(
                            unixTimeName, new FunctionParams(false, unixTimeArgs));

                    entry.setValue(unixTimeFunc);
                } else if (funcName.equalsIgnoreCase("default_value")) {
                    entry.setValue(funcExpr.getChild(0));
                }
            }
        }
    }

    // Called from init, construct source tuple information
    private void initParams(ParamCreateContext context) throws AnalysisException, InternalException {
        TBrokerScanRangeParams params = new TBrokerScanRangeParams();
        context.params = params;

        BrokerFileGroup fileGroup = context.fileGroup;
        params.setColumn_separator(fileGroup.getValueSeparator().getBytes(Charset.forName("UTF-8"))[0]);
        params.setLine_delimiter(fileGroup.getLineDelimiter().getBytes(Charset.forName("UTF-8"))[0]);

        // Parse partition information
        List<Long> partitionIds = fileGroup.getPartitionIds();
        if (partitionIds != null && partitionIds.size() > 0) {
            params.setPartition_ids(partitionIds);
            createPartitionInfos();
        }

        params.setProperties(brokerDesc.getProperties());

        context.exprMap = fileGroup.getExprColumnMap();
        parseExprMap(context.exprMap);

        // Generate expr
        List<String> valueNames = fileGroup.getValueNames();
        if (valueNames == null) {
            valueNames = Lists.newArrayList();
            for (Column column : targetTable.getBaseSchema()) {
                valueNames.add(column.getName());
            }
        }

        // This tuple descriptor is used for file of
        TupleDescriptor srcTupleDesc = analyzer.getDescTbl().createTupleDescriptor();
        context.tupleDescriptor = srcTupleDesc;

        Map<String, SlotDescriptor> slotDescByName = Maps.newHashMap();
        context.slotDescByName = slotDescByName;
        for (String value : valueNames) {
            SlotDescriptor slotDesc = analyzer.getDescTbl().addSlotDescriptor(srcTupleDesc);
            slotDesc.setType(ScalarType.createType(PrimitiveType.VARCHAR));
            slotDesc.setIsMaterialized(true);
            slotDesc.setIsNullable(false);
            slotDescByName.put(value, slotDesc);

            params.addToSrc_slot_ids(slotDesc.getId().asInt());
        }
        params.setSrc_tuple_id(srcTupleDesc.getId().asInt());
    }

    private void finalizeParams(ParamCreateContext context) throws InternalException, AnalysisException {
        Map<String, SlotDescriptor> slotDescByName = context.slotDescByName;
        Map<String, Expr> exprMap = context.exprMap;
        // Analyze expr map
        if (exprMap != null) {
            for (Map.Entry<String, Expr> entry : exprMap.entrySet()) {
                ExprSubstitutionMap smap = new ExprSubstitutionMap();

                List<SlotRef> slots = Lists.newArrayList();
                entry.getValue().collect(SlotRef.class, slots);

                for (SlotRef slot : slots) {
                    SlotDescriptor slotDesc = slotDescByName.get(slot.getColumnName());
                    if (slotDesc == null) {
                        throw new InternalException("Unknown slot");
                    }
                    smap.getLhs().add(slot);
                    smap.getRhs().add(new SlotRef(slotDesc));
                }

                Expr expr = entry.getValue().clone(smap);
                expr.analyze(analyzer);
                exprMap.put(entry.getKey(), expr);
            }
        }

        for (SlotDescriptor destSlotDesc : desc.getSlots()) {
            if (!destSlotDesc.isMaterialized()) {
                continue;
            }
            Expr expr = null;
            if (exprMap != null) {
                expr = exprMap.get(destSlotDesc.getColumn().getName());
            }
            if (expr == null) {
                SlotDescriptor srcSlotDesc = slotDescByName.get(destSlotDesc.getColumn().getName());
                if (srcSlotDesc != null) {
                    // If dest is allow null, we set source to nullable
                    if (destSlotDesc.getColumn().isAllowNull()) {
                        srcSlotDesc.setIsNullable(true);
                    }
                    expr = new SlotRef(srcSlotDesc);
                } else {
                    Column column = destSlotDesc.getColumn();
                    if (column.getDefaultValue() != null) {
                        expr = new StringLiteral(destSlotDesc.getColumn().getDefaultValue());
                    } else {
                        if (column.isAllowNull()) {
                            expr = NullLiteral.create(column.getType());
                        } else {
                            throw new InternalException("Unknown slot ref("
                                    + destSlotDesc.getColumn().getName() + ") in source file");
                        }
                    }
                }
            }

            expr = castToSlot(destSlotDesc, expr);
            context.params.putToExpr_of_dest_slot(destSlotDesc.getId().asInt(), expr.treeToThrift());
        }
        context.params.setDest_tuple_id(desc.getId().asInt());
        // Need re compute memory layout after set some slot descriptor to nullable
        context.tupleDescriptor.computeMemLayout();
    }

    private Expr castToSlot(SlotDescriptor slotDesc, Expr expr) throws AnalysisException {
        PrimitiveType destType = slotDesc.getType().getPrimitiveType();
        PrimitiveType srcType = expr.getType().getPrimitiveType();

        if (destType.isStringType()) {
            if (srcType.isStringType()) {
                return expr;
            } else {
                CastExpr castExpr = new CastExpr(Type.VARCHAR, expr, true);
                castExpr.analyze(analyzer);
                return castExpr;
            }
        } else if (destType != srcType) {
            CastExpr castExpr = new CastExpr(slotDesc.getType(), expr, true);
            castExpr.analyze(analyzer);
            return castExpr;
        }

        return expr;
    }

    private TScanRangeLocations newLocations(TBrokerScanRangeParams params, String brokerName)
            throws InternalException {
        List<Backend> candidateBes = Lists.newArrayList();
        // Get backend
        int numBe = Math.min(3, backends.size());
        for (int i = 0; i < numBe; ++i) {
            candidateBes.add(backends.get(nextBe++));
            nextBe = nextBe % backends.size();
        }

        // Generate on broker scan range
        TBrokerScanRange brokerScanRange = new TBrokerScanRange();
        brokerScanRange.setParams(params);
        // TODO(zc):
        int numBroker = Math.min(3, numBe);
        for (int i = 0; i < numBroker; ++i) {
            BrokerMgr.BrokerAddress brokerAddress = null;
            try {
                brokerAddress = Catalog.getInstance().getBrokerMgr().getBroker(
                        brokerName, candidateBes.get(i).getHost());
            } catch (AnalysisException e) {
                throw new InternalException(e.getMessage());
            }
            brokerScanRange.addToBroker_addresses(new TNetworkAddress(brokerAddress.ip, brokerAddress.port));
        }

        // Scan range
        TScanRange scanRange = new TScanRange();
        scanRange.setBroker_scan_range(brokerScanRange);

        // Locations
        TScanRangeLocations locations = new TScanRangeLocations();
        locations.setScan_range(scanRange);
        for (Backend be : candidateBes) {
            TScanRangeLocation location = new TScanRangeLocation();
            location.setBackend_id(be.getId());
            location.setServer(new TNetworkAddress(be.getHost(), be.getBePort()));
            locations.addToLocations(location);
        }

        return locations;
    }

    private TBrokerScanRange brokerScanRange(TScanRangeLocations locations) {
        return locations.scan_range.broker_scan_range;
    }

    private void parseBrokerFile(String path, ArrayList<TBrokerFileStatus> fileStatuses) throws InternalException {
        BrokerMgr.BrokerAddress brokerAddress = null;
        try {
            String localIP = FrontendOptions.getLocalHostAddress();
            brokerAddress = Catalog.getInstance().getBrokerMgr().getBroker(brokerDesc.getName(), localIP);
        } catch (AnalysisException e) {
            throw new InternalException(e.getMessage());
        }
        TNetworkAddress address = new TNetworkAddress(brokerAddress.ip, brokerAddress.port);
        TPaloBrokerService.Client client = null;
        try {
            client  = ClientPool.brokerPool.borrowObject(address);
        } catch (Exception e) {
            try {
                client  = ClientPool.brokerPool.borrowObject(address);
            } catch (Exception e1) {
                throw new InternalException("Create connection to broker(" + address + ") failed.");
            }
        }
        boolean failed = true;
        try {
            TBrokerListPathRequest request = new TBrokerListPathRequest(
                    TBrokerVersion.VERSION_ONE, path, false, brokerDesc.getProperties());
            TBrokerListResponse tBrokerListResponse = null;
            try {
                tBrokerListResponse = client.listPath(request);
            } catch (TException e) {
                ClientPool.brokerPool.reopen(client);
                tBrokerListResponse = client.listPath(request);
            }
            if (tBrokerListResponse.getOpStatus().getStatusCode() != TBrokerOperationStatusCode.OK) {
                throw new InternalException("Broker list path failed.path=" + path
                        + ",broker=" + address + ",msg=" + tBrokerListResponse.getOpStatus().getMessage());
            }
            failed = false;
            for (TBrokerFileStatus tBrokerFileStatus : tBrokerListResponse.getFiles()) {
                if (tBrokerFileStatus.isDir) {
                    continue;
                }
                fileStatuses.add(tBrokerFileStatus);
            }
        } catch (TException e) {
            LOG.warn("Broker list path exception, path={}, address={}, exception={}", path, address, e);
            throw new InternalException("Broker list path exception.path=" + path + ",broker=" + address);
        } finally {
            if (failed) {
                ClientPool.brokerPool.invalidateObject(address, client);
            } else {
                ClientPool.brokerPool.returnObject(address, client);
            }
        }
    }

    private void getAllBrokerFileStatus() throws InternalException {
        int filesAdded = 0;
        fileStatusesList = Lists.newArrayList();
        for (BrokerFileGroup fileGroup : fileGroups) {
            ArrayList<TBrokerFileStatus> fileStatuses = Lists.newArrayList();
            for (String path : fileGroup.getFilePathes()) {
                parseBrokerFile(path, fileStatuses);
            }
            fileStatusesList.add(fileStatuses);
            filesAdded += fileStatuses.size();
            for (TBrokerFileStatus fstatus : fileStatuses) {
                LOG.info("Add file status is {}", fstatus);
            }
        }

        if (isLoad() && filesAdded == 0) {
            throw new InternalException("No source file in this table(" + targetTable.getName() + ").");
        }

        totalBytes = 0;
        for (ArrayList<TBrokerFileStatus> fileStatuses : fileStatusesList) {
            Collections.sort(fileStatuses, T_BROKER_FILE_STATUS_COMPARATOR);
            for (TBrokerFileStatus fileStatus : fileStatuses) {
                totalBytes += fileStatus.size;
            }
        }

        numInstances = (int) (totalBytes / Config.min_bytes_per_broker_scanner);
        numInstances = Math.min(backends.size(), numInstances);
        numInstances = Math.min(numInstances, Config.max_broker_concurrency);
        numInstances = Math.max(1, numInstances);

        bytesPerInstance = totalBytes / numInstances + 1;
    }

    private void assignBackends() throws InternalException {
        backends = Lists.newArrayList();
        for (Backend be : Catalog.getCurrentSystemInfo().getIdToBackend().values()) {
            if (be.isAlive()) {
                backends.add(be);
            }
        }
        if (backends.isEmpty()) {
            throw new InternalException("No Alive backends");
        }
        Collections.shuffle(backends, random);
    }

    private TFileFormatType formatType(String path) {
        String lowerCasePath = path.toLowerCase();
        if (lowerCasePath.endsWith(".gz")) {
            return TFileFormatType.FORMAT_CSV_GZ;
        } else if (lowerCasePath.endsWith(".bz2")) {
            return TFileFormatType.FORMAT_CSV_BZ2;
        } else if (lowerCasePath.endsWith(".lz4")) {
            return TFileFormatType.FORMAT_CSV_LZ4FRAME;
        } else if (lowerCasePath.endsWith(".lzo")) {
            return TFileFormatType.FORMAT_CSV_LZOP;
        } else {
            return TFileFormatType.FORMAT_CSV_PLAIN;
        }
    }

    private void processFileGroup(
            TBrokerScanRangeParams params,
            ArrayList<TBrokerFileStatus> fileStatuses)
            throws InternalException {
        if (fileStatuses == null || fileStatuses.isEmpty()) {
            return;
        }

        TScanRangeLocations curLocations = newLocations(params, brokerDesc.getName());
        long curInstanceBytes = 0;
        long curFileOffset = 0;
        for (int i = 0; i < fileStatuses.size(); ) {
            TBrokerFileStatus fileStatus = fileStatuses.get(i);
            long leftBytes = fileStatus.size - curFileOffset;
            long tmpBytes = curInstanceBytes + leftBytes;
            TFileFormatType formatType = formatType(fileStatus.path);
            if (tmpBytes > bytesPerInstance) {
                // Now only support split plain text
                if (formatType == TFileFormatType.FORMAT_CSV_PLAIN && fileStatus.isSplitable) {
                    long rangeBytes = bytesPerInstance - curInstanceBytes;

                    TBrokerRangeDesc rangeDesc = new TBrokerRangeDesc();
                    rangeDesc.setFile_type(TFileType.FILE_BROKER);
                    rangeDesc.setFormat_type(formatType);
                    rangeDesc.setPath(fileStatus.path);
                    rangeDesc.setSplittable(fileStatus.isSplitable);
                    rangeDesc.setStart_offset(curFileOffset);
                    rangeDesc.setSize(rangeBytes);
                    brokerScanRange(curLocations).addToRanges(rangeDesc);

                    curFileOffset += rangeBytes;
                } else {
                    TBrokerRangeDesc rangeDesc = new TBrokerRangeDesc();
                    rangeDesc.setFile_type(TFileType.FILE_BROKER);
                    rangeDesc.setFormat_type(formatType);
                    rangeDesc.setPath(fileStatus.path);
                    rangeDesc.setSplittable(fileStatus.isSplitable);
                    rangeDesc.setStart_offset(curFileOffset);
                    rangeDesc.setSize(leftBytes);
                    brokerScanRange(curLocations).addToRanges(rangeDesc);

                    curFileOffset = 0;
                    i++;
                }

                // New one scan
                locationsList.add(curLocations);
                curLocations = newLocations(params, brokerDesc.getName());
                curInstanceBytes = 0;

            } else {
                TBrokerRangeDesc rangeDesc = new TBrokerRangeDesc();
                rangeDesc.setFile_type(TFileType.FILE_BROKER);
                rangeDesc.setFormat_type(formatType);
                rangeDesc.setPath(fileStatus.path);
                rangeDesc.setSplittable(fileStatus.isSplitable);
                rangeDesc.setStart_offset(curFileOffset);
                rangeDesc.setSize(leftBytes);
                brokerScanRange(curLocations).addToRanges(rangeDesc);

                curFileOffset = 0;
                curInstanceBytes += leftBytes;
                i++;
            }
        }

        // Put the last file
        if (brokerScanRange(curLocations).isSetRanges()) {
            locationsList.add(curLocations);
        }
    }

    @Override
    public void finalize(Analyzer analyzer) throws InternalException {
        locationsList = Lists.newArrayList();

        for (int i = 0; i < fileGroups.size(); ++i) {
            ArrayList<TBrokerFileStatus> fileStatuses = fileStatusesList.get(i);
            if (fileStatuses.isEmpty()) {
                continue;
            }
            ParamCreateContext context = paramCreateContexts.get(i);
            try {
                finalizeParams(context);
            } catch (AnalysisException e) {
                throw new InternalException(e.getMessage());
            }
            processFileGroup(context.params, fileStatuses);
        }
        if (LOG.isDebugEnabled()) {
            for (TScanRangeLocations locations : locationsList) {
                LOG.debug("Scan range is {}", locations);
            }
        }
    }

    @Override
    protected void toThrift(TPlanNode msg) {
        msg.node_type = TPlanNodeType.BROKER_SCAN_NODE;
        TBrokerScanNode brokerScanNode = new TBrokerScanNode(desc.getId().asInt());
        if (partitionInfos != null) {
            brokerScanNode.setPartition_exprs(Expr.treesToThrift(partitionExprs));
            brokerScanNode.setPartition_infos(DataSplitSink.EtlRangePartitionInfo.listToNonDistThrift(partitionInfos));
        }
        msg.setBroker_scan_node(brokerScanNode);
    }

    @Override
    public List<TScanRangeLocations> getScanRangeLocations(long maxScanRangeLength) {
        return locationsList;
    }

    @Override
    public int getNumInstances() {
        return numInstances;
    }

    @Override
    protected String getNodeExplainString(String prefix, TExplainLevel detailLevel) {
        StringBuilder output = new StringBuilder();
        if (!isLoad()) {
            BrokerTable brokerTable = (BrokerTable) targetTable;
            output.append(prefix).append("TABLE: ").append(brokerTable.getName()).append("\n");
            output.append(prefix).append("PATH: ")
                    .append(Joiner.on(",").join(brokerTable.getPaths())).append("\",\n");
        }
        if (brokerDesc != null) {
            output.append(prefix).append("BROKER: ").append(brokerDesc.getName()).append("\n");
        }
        return output.toString();
    }
}
