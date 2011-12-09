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

package com.akiban.sql.optimizer.rule;

import com.akiban.sql.optimizer.plan.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/** Move WHERE clauses closer to their table origin.
 * This rule runs after flattening has been laid out.
 *
 * Note: <i>prepone</i>, while not an American or British English
 * word, is the transparent opposite of <i>postpone</i>.
 */
// TODO: Something similar is needed to handle moving HAVING
// conditions on the group by fields across the aggregation boundary
// and WHERE conditions on subqueries (views) into the subquery
// itself. These need to run earlier to affect indexing. Not sure how
// to integrate all these. Maybe move everything earlier on and then
// recognize joins of such filtered tables as Joinable.
public class SelectPreponer extends BaseRule
{
    private static final Logger logger = LoggerFactory.getLogger(SelectPreponer.class);

    @Override
    protected Logger getLogger() {
        return logger;
    }

    static class TableOriginFinder implements PlanVisitor, ExpressionVisitor {
        List<PlanNode> origins = new ArrayList<PlanNode>();

        public void find(PlanNode root) {
            root.accept(this);
        }

        public List<PlanNode> getOrigins() {
            return origins;
        }

        @Override
        public boolean visitEnter(PlanNode n) {
            return visit(n);
        }

        @Override
        public boolean visitLeave(PlanNode n) {
            return true;
        }

        @Override
        public boolean visit(PlanNode n) {
            if (n instanceof IndexScan) {
                origins.add(n);
            }
            else if (n instanceof TableLoader) {
                if (n instanceof BasePlanWithInput) {
                    PlanNode input = ((BasePlanWithInput)n).getInput();
                    if (!((input instanceof TableLoader) ||
                          (input instanceof IndexScan))) {
                        // Will put input in, so don't bother putting both in.
                        origins.add(n);
                    }
                }
                else {
                    origins.add(n);
                }
            }
            return true;
        }

        @Override
        public boolean visitEnter(ExpressionNode n) {
            return visit(n);
        }

        @Override
        public boolean visitLeave(ExpressionNode n) {
            return true;
        }

        @Override
        public boolean visit(ExpressionNode n) {
            return true;
        }
    }

    @Override
    public void apply(PlanContext plan) {
        TableOriginFinder finder = new TableOriginFinder();
        finder.find(plan.getPlan());
        Preponer preponer = new Preponer();
        for (PlanNode origin : finder.getOrigins()) {
            preponer.pullToward(origin);
        }
    }
    
    static class Preponer {
        Map<Select,ConditionDependencyAnalyzer> selectDependencies;
        Map<TableSource,PlanNode> loaders;
        Map<ExpressionNode,PlanNode> indexColumns;
        
        public Preponer() {
        }

        protected void pullToward(PlanNode node) {
            loaders = new HashMap<TableSource,PlanNode>();
            PlanNode prev = null;
            if (node instanceof IndexScan) {
                indexColumns = new HashMap<ExpressionNode,PlanNode>();
                for (ExpressionNode column : ((IndexScan)node).getColumns())
                    indexColumns.put(column, node);
                prev = node;
                node = node.getOutput();
            }
            else {
                indexColumns = null;
            }
            while (node instanceof TableLoader) {
                for (TableSource table : ((TableLoader)node).getTables()) {
                    loaders.put(table, node);
                }
                prev = node;
                node = node.getOutput();
            }
            boolean sawJoin = false;
            while (true) {
                if (node instanceof Flatten) {
                    // Limit to tables that are inner joined (and on the
                    // outer side of outer joins.)
                    Set<TableSource> inner = ((Flatten)node).getInnerJoinedTables();
                    loaders.keySet().retainAll(inner);
                    if (indexColumns != null) {
                        Iterator<ExpressionNode> iter = indexColumns.keySet().iterator();
                        while (iter.hasNext()) {
                            ExpressionNode expr = iter.next();
                            if (expr.isColumn() && 
                                !inner.contains(((ColumnExpression)expr).getTable()))
                                iter.remove();
                        }
                    }
                    sawJoin = true;
                }
                else if (node instanceof MapJoin) {
                    switch (((MapJoin)node).getJoinType()) {
                    case INNER:
                        break;
                    case LEFT:
                        if (prev == ((MapJoin)node).getInner())
                            return;
                        break;
                    default:
                        return;
                    }
                    sawJoin = true;
                }
                else if (node instanceof Product) {
                    // Only inner right now.
                    sawJoin = true;
                }
                else
                    break;
                prev = node;
                node = node.getOutput();
            }
            if (!sawJoin ||
                (loaders.isEmpty() &&
                 ((indexColumns == null) || indexColumns.isEmpty()))) {
                // We didn't see any joins (conditions will follow
                // loading directly -- nothing to move over) or ran
                // out of things to move.
                return;
            }
            if (node instanceof Select) {
                Select select = (Select)node;
                moveConditions(getDependencies(select), select.getConditions());
            }
        }

        protected ConditionDependencyAnalyzer getDependencies(Select select) {
            if (selectDependencies == null)
                selectDependencies = new HashMap<Select,ConditionDependencyAnalyzer>();
            ConditionDependencyAnalyzer dependencies = selectDependencies.get(select);
            if (dependencies == null) {
                dependencies = new ConditionDependencyAnalyzer(select);
                selectDependencies.put(select, dependencies);
            }
            return dependencies;
        }

        // Have a straight path to these conditions and know where
        // tables came from.  See what can be moved back there.
        protected void moveConditions(ConditionDependencyAnalyzer dependencies,
                                      ConditionList conditions) {
            Iterator<ConditionExpression> iter = conditions.iterator();
            while (iter.hasNext()) {
                ConditionExpression condition = iter.next();
                PlanNode moveTo = canMove(dependencies, condition);
                if (moveTo != null) {
                    moveCondition(condition, moveTo);
                    iter.remove();
                }
            }
        }

        // Return where this condition can move.
        // TODO: Can move to after subset of joins once enough tables are joined,
        // by breaking apart Flatten.
        protected PlanNode canMove(ConditionDependencyAnalyzer dependencies,
                                   ConditionExpression condition) {
            ColumnSource singleTable = dependencies.analyze(condition);
            if (indexColumns != null) {
                // Can check the index column before it's used for lookup.
                PlanNode loader = 
                    getSingleIndexLoader(dependencies.getReferencedColumns());
                if (loader != null)
                    return loader;
            }
            if (singleTable == null)
                return null;
            else
                return loaders.get(singleTable);
        }

        // If all the referenced columns come from the same index, return it.
        protected PlanNode getSingleIndexLoader(Set<ColumnExpression> columns) {
            PlanNode single = null;
            for (ColumnExpression column : columns) {
                PlanNode loader = indexColumns.get(column);
                if (loader == null)
                    return null;
                if (single == null)
                    single = loader;
                else if (single != loader)
                    return null;
            }
            return single;
        }

        // Move the given condition to a Select that is right after the given node.
        protected void moveCondition(ConditionExpression condition, 
                                     PlanNode before) {
            Select select = null;
            PlanWithInput after = before.getOutput();
            if (after instanceof Select)
                select = (Select)after;
            else {
                select = new Select(before, new ConditionList(1));
                after.replaceInput(before, select);
            }
            select.getConditions().add(condition);
        }

    }

    /** If this condition involves only a single table, return it. */
    // TODO: Lots of room for improvement here, even with that simple contract.
    public static TableSource getSingleTableConditionTable(ConditionExpression condition) {
        if (condition instanceof ComparisonCondition) {
            ComparisonCondition comp = (ComparisonCondition)condition;
            ExpressionNode left = comp.getLeft();
            ExpressionNode right = comp.getRight();
            if (!(left.isColumn()))
                return null;
            ColumnSource table = null;
            table = ((ColumnExpression)left).getTable();
            if (!(table instanceof TableSource))
                return null;
            if (!isConstant(right))
                return null;
            return (TableSource)table;
        }
        else if (condition instanceof BooleanOperationExpression) {
            BooleanOperationExpression bexpr = (BooleanOperationExpression)condition;
            TableSource left = getSingleTableConditionTable(bexpr.getLeft());
            TableSource right = getSingleTableConditionTable(bexpr.getRight());
            if (left == right) return left;
        }
        else if (condition instanceof LogicalFunctionCondition) {
            TableSource single = null;
            for (ExpressionNode operand : ((LogicalFunctionCondition)
                                           condition).getOperands()) {
                TableSource osingle = getSingleTableConditionTable((ConditionExpression)
                                                                   operand);
                if (single == null) {
                    if (osingle == null)
                        return null;
                    single = osingle;
                }
                else if (single != osingle)
                    return null;
            }
            return single;
        }
        return null;
    }

    // TODO: Column from outer table okay, too. Need general predicate for that.
    protected static boolean isConstant(ExpressionNode node) {
        if ((node instanceof ConstantExpression) || 
            (node instanceof ParameterExpression))
            return true;
        if (node instanceof CastExpression)
            return isConstant(((CastExpression)node).getOperand());
        return false;
    }

}
