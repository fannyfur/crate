/*
 * Licensed to Crate under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.  Crate licenses this file
 * to you under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial
 * agreement.
 */

package io.crate.planner.operators;

import io.crate.analyze.OrderBy;
import io.crate.analyze.WindowDefinition;
import io.crate.collections.Lists2;
import io.crate.data.Row;
import io.crate.exceptions.UnsupportedFeatureException;
import io.crate.execution.dsl.projection.OrderedTopNProjection;
import io.crate.execution.dsl.projection.WindowAggProjection;
import io.crate.execution.dsl.projection.builder.InputColumns;
import io.crate.execution.dsl.projection.builder.ProjectionBuilder;
import io.crate.expression.symbol.InputColumn;
import io.crate.expression.symbol.Symbol;
import io.crate.expression.symbol.WindowFunction;
import io.crate.planner.ExecutionPlan;
import io.crate.planner.ExplainLeaf;
import io.crate.planner.Merge;
import io.crate.planner.PlannerContext;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.crate.planner.operators.LogicalPlanner.extractColumns;

public class WindowAgg extends OneInputPlan {

    private final WindowDefinition windowDefinition;
    private final List<WindowFunction> windowFunctions;
    private final List<Symbol> standalone;

    static LogicalPlan.Builder create(LogicalPlan.Builder source, List<WindowFunction> windowFunctions) {
        if (windowFunctions.isEmpty()) {
            return source;
        }

        for (WindowFunction windowFunction : windowFunctions) {
            WindowDefinition windowDefinition = windowFunction.windowDefinition();
            if (!windowDefinition.windowFrameDefinition().equals(WindowDefinition.DEFAULT_WINDOW_FRAME)) {
                throw new UnsupportedFeatureException("Custom frame definitions are not supported");
            }
        }

        return (tableStats, usedBeforeNextFetch) -> {
            HashSet<Symbol> allUsedColumns = new HashSet<>(usedBeforeNextFetch);
            Set<Symbol> columnsUsedInFunctions = extractColumns(windowFunctions);
            LinkedHashMap<WindowDefinition, ArrayList<WindowFunction>> groupedFunctions = new LinkedHashMap<>();
            for (WindowFunction windowFunction : windowFunctions) {
                WindowDefinition windowDefinition = windowFunction.windowDefinition();
                OrderBy orderBy = windowDefinition.orderBy();
                if (orderBy != null) {
                    columnsUsedInFunctions.addAll(extractColumns(orderBy.orderBySymbols()));
                }
                columnsUsedInFunctions.addAll(extractColumns(windowDefinition.partitions()));
                ArrayList<WindowFunction> functions = groupedFunctions.computeIfAbsent(windowDefinition, w -> new ArrayList<>());
                functions.add(windowFunction);
            }
            allUsedColumns.addAll(columnsUsedInFunctions);
            LogicalPlan sourcePlan = source.build(tableStats, allUsedColumns);

            LogicalPlan lastWindowAgg = sourcePlan;
            for (Map.Entry<WindowDefinition, ArrayList<WindowFunction>> entry : groupedFunctions.entrySet()) {
                /**
                 * Pass along the source outputs as standalone symbols as they might be required in cases like:
                 *      select x, avg(x) OVER() from t;
                 */
                lastWindowAgg = new WindowAgg(lastWindowAgg, entry.getKey(), entry.getValue(), lastWindowAgg.outputs());
            }
            return lastWindowAgg;
        };
    }

    private WindowAgg(LogicalPlan source, WindowDefinition windowDefinition, List<WindowFunction> windowFunctions, List<Symbol> standalone) {
        super(source, Lists2.concat(windowFunctions, standalone));
        this.windowDefinition = windowDefinition;
        this.windowFunctions = windowFunctions;
        this.standalone = standalone;
    }

    List<WindowFunction> windowFunctions() {
        return windowFunctions;
    }

    @Override
    protected LogicalPlan updateSource(LogicalPlan newSource, SymbolMapper mapper) {
        return new WindowAgg(newSource, windowDefinition, windowFunctions, standalone);
    }

    @Override
    public ExecutionPlan build(PlannerContext plannerContext,
                               ProjectionBuilder projectionBuilder,
                               int limit,
                               int offset,
                               @Nullable OrderBy order,
                               @Nullable Integer pageSizeHint,
                               Row params,
                               SubQueryResults subQueryResults) {
        ExecutionPlan sourcePlan = source.build(
            plannerContext,
            projectionBuilder,
            limit,
            offset,
            order,
            pageSizeHint,
            params,
            subQueryResults
        );

        sourcePlan = Merge.ensureOnHandler(sourcePlan, plannerContext);

        InputColumns.SourceSymbols sourceSymbols = new InputColumns.SourceSymbols(source.outputs());
        WindowDefinition windowDefinitionWithICs = convertSymbolsToInputColumns(windowDefinition, sourceSymbols);
        List<Symbol> standaloneWithInputs = InputColumns.create(this.standalone, sourceSymbols);

        LinkedHashMap<WindowFunction, List<Symbol>> functionsWithInputs = new LinkedHashMap<>(windowFunctions.size(), 1f);
        for (WindowFunction windowFunction : windowFunctions) {
            WindowFunction windowFunctionSymbol = (WindowFunction) InputColumns.create(windowFunction, sourceSymbols);
            List<Symbol> inputs = InputColumns.create(windowFunction.arguments(), sourceSymbols);
            functionsWithInputs.put(windowFunctionSymbol, inputs);
        }

        OrderBy orderBy = windowDefinitionWithICs.orderBy();
        List<Symbol> orderByICs = new ArrayList<>();
        if(!windowDefinition.partitions().isEmpty() || orderBy != null) {
            List<Boolean> reverseFlags = new ArrayList<>();
            List<Boolean> nullsFirst = new ArrayList<>();

            for (Symbol partitionIC : windowDefinitionWithICs.partitions()) {
                orderByICs.add(partitionIC);
                reverseFlags.add(false);
                nullsFirst.add(null);
            }

            if(orderBy != null) {
                List<Symbol> symbols = orderBy.orderBySymbols();
                for (int i = 0; i < symbols.size(); i++) {
                    Symbol symbol = symbols.get(i);
                    int indexOfOrderByIC = orderByICs.indexOf(symbol);
                    boolean reverseFlagForSymbol = orderBy.reverseFlags()[i];
                    Boolean nullFirstForSymbol = orderBy.nullsFirst()[i];
                    if (indexOfOrderByIC != -1) {
                        reverseFlags.set(indexOfOrderByIC, reverseFlagForSymbol);
                        nullsFirst.set(indexOfOrderByIC, nullFirstForSymbol);
                    } else {
                        orderByICs.add(symbol);
                        reverseFlags.add(reverseFlagForSymbol);
                        nullsFirst.add(nullFirstForSymbol);
                    }
                }
            }

            List<Symbol> outputs = InputColumns.create(source.outputs(), sourceSymbols);

            boolean[] reverseFlagsArray = new boolean[reverseFlags.size()];
            for (int i = 0; i < reverseFlags.size(); i++) {
                reverseFlagsArray[i] = reverseFlags.get(i);
            }
            OrderedTopNProjection topNProjection = new OrderedTopNProjection(
                Limit.limitAndOffset(limit, offset),
                0,
                outputs,
                orderByICs,
                reverseFlagsArray,
                nullsFirst.toArray(new Boolean[0])
            );
            sourcePlan.addProjection(
                topNProjection,
                limit,
                offset,
                null
            );
        }

        int[] orderByIndexes = new int[orderByICs.size()];
        int index = 0;
        for (Symbol ic : orderByICs) {
            assert ic instanceof InputColumn : "Window ordering should be expressed as ICs at this stage";
            orderByIndexes[index] = ((InputColumn) ic).index();
            index++;

        }

        sourcePlan.addProjection(new WindowAggProjection(windowDefinitionWithICs, functionsWithInputs, standaloneWithInputs, orderByIndexes));
        return sourcePlan;
    }

    private WindowDefinition convertSymbolsToInputColumns(WindowDefinition windowDefinition,
                                                          InputColumns.SourceSymbols sourceSymbols) {
        List<Symbol> partitionsInputColumns = InputColumns.create(windowDefinition.partitions(), sourceSymbols);
        OrderBy orderBy = windowDefinition.orderBy();
        if (orderBy != null) {
            orderBy = new OrderBy(
                InputColumns.create(orderBy.orderBySymbols(), sourceSymbols),
                orderBy.reverseFlags(),
                orderBy.nullsFirst()
            );
        }

        return new WindowDefinition(partitionsInputColumns, orderBy, windowDefinition.windowFrameDefinition());
    }

    @Override
    public <C, R> R accept(LogicalPlanVisitor<C, R> visitor, C context) {
        return visitor.visitWindowAgg(this, context);
    }

    @Override
    public List<Symbol> outputs() {
        return outputs;
    }

    @Override
    public String toString() {
        return "WindowAgg{[" + ExplainLeaf.printList(windowFunctions) + "]}";
    }
}
