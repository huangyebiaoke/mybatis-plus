/*
 * Copyright (c) 2011-2022, baomidou (jobob@qq.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.baomidou.mybatisplus.extension.plugins.inner;

import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import com.baomidou.mybatisplus.extension.parser.JsqlParserSupport;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import net.sf.jsqlparser.expression.*;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.ExistsExpression;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 多表条件处理基对象，从原有的 {@link TenantLineInnerInterceptor} 拦截器中提取出来
 *
 * @author houkunlin
 * @since 3.5.2
 */
@Data
@NoArgsConstructor
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
@SuppressWarnings({"rawtypes"})
public abstract class BaseMultiTableInnerInterceptor extends JsqlParserSupport implements InnerInterceptor {

    protected void processSelectBody(SelectBody selectBody, final String whereSegment) {
        if (selectBody == null) {
            return;
        }
        if (selectBody instanceof PlainSelect) {
            processPlainSelect((PlainSelect) selectBody, whereSegment);
        } else if (selectBody instanceof WithItem) {
            WithItem withItem = (WithItem) selectBody;
            processSelectBody(withItem.getSubSelect().getSelectBody(), whereSegment);
        } else {
            SetOperationList operationList = (SetOperationList) selectBody;
            List<SelectBody> selectBodyList = operationList.getSelects();
            if (CollectionUtils.isNotEmpty(selectBodyList)) {
                selectBodyList.forEach(body -> processSelectBody(body, whereSegment));
            }
        }
    }

    /**
     * delete update 语句 where 处理
     */
    protected Expression andExpression(Table table, Expression where, final String whereSegment) {
        //获得where条件表达式
        final Expression expression = buildTableExpression(table, whereSegment);
        if (null != where) {
            if (where instanceof OrExpression) {
                return new AndExpression(expression, new Parenthesis(where));
            } else {
                return new AndExpression(expression, where);
            }
        }
        return expression;
    }

    /**
     * 处理 PlainSelect
     */
    protected void processPlainSelect(final PlainSelect plainSelect, final String whereSegment) {
        //#3087 github
        List<SelectItem> selectItems = plainSelect.getSelectItems();
        if (CollectionUtils.isNotEmpty(selectItems)) {
            selectItems.forEach(selectItem -> processSelectItem(selectItem, whereSegment));
        }

        // 处理 where 中的子查询
        Expression where = plainSelect.getWhere();
        processWhereSubSelect(where, whereSegment);

        // 处理 fromItem
        FromItem fromItem = plainSelect.getFromItem();
        List<Table> list = processFromItem(fromItem, whereSegment);
        List<Table> mainTables = new ArrayList<>(list);

        // 处理 join
        List<Join> joins = plainSelect.getJoins();
        if (CollectionUtils.isNotEmpty(joins)) {
            mainTables = processJoins(mainTables, joins, whereSegment);
        }

        // 当有 mainTable 时，进行 where 条件追加
        if (CollectionUtils.isNotEmpty(mainTables)) {
            plainSelect.setWhere(builderExpression(where, mainTables, whereSegment));
        }
    }

    private List<Table> processFromItem(FromItem fromItem, final String whereSegment) {
        // 处理括号括起来的表达式
        while (fromItem instanceof ParenthesisFromItem) {
            fromItem = ((ParenthesisFromItem) fromItem).getFromItem();
        }

        List<Table> mainTables = new ArrayList<>();
        // 无 join 时的处理逻辑
        if (fromItem instanceof Table) {
            Table fromTable = (Table) fromItem;
            mainTables.add(fromTable);
        } else if (fromItem instanceof SubJoin) {
            // SubJoin 类型则还需要添加上 where 条件
            List<Table> tables = processSubJoin((SubJoin) fromItem, whereSegment);
            mainTables.addAll(tables);
        } else {
            // 处理下 fromItem
            processOtherFromItem(fromItem, whereSegment);
        }
        return mainTables;
    }

    /**
     * 处理where条件内的子查询
     * <p>
     * 支持如下:
     * <ol>
     *     <li>in</li>
     *     <li>=</li>
     *     <li>&gt;</li>
     *     <li>&lt;</li>
     *     <li>&gt;=</li>
     *     <li>&lt;=</li>
     *     <li>&lt;&gt;</li>
     *     <li>EXISTS</li>
     *     <li>NOT EXISTS</li>
     * </ol>
     * <p>
     * 前提条件:
     * 1. 子查询必须放在小括号中
     * 2. 子查询一般放在比较操作符的右边
     *
     * @param where where 条件
     */
    protected void processWhereSubSelect(Expression where, final String whereSegment) {
        if (where == null) {
            return;
        }
        if (where instanceof FromItem) {
            processOtherFromItem((FromItem) where, whereSegment);
            return;
        }
        if (where.toString().indexOf("SELECT") > 0) {
            // 有子查询
            if (where instanceof BinaryExpression) {
                // 比较符号 , and , or , 等等
                BinaryExpression expression = (BinaryExpression) where;
                processWhereSubSelect(expression.getLeftExpression(), whereSegment);
                processWhereSubSelect(expression.getRightExpression(), whereSegment);
            } else if (where instanceof InExpression) {
                // in
                InExpression expression = (InExpression) where;
                Expression inExpression = expression.getRightExpression();
                if (inExpression instanceof SubSelect) {
                    processSelectBody(((SubSelect) inExpression).getSelectBody(), whereSegment);
                }
            } else if (where instanceof ExistsExpression) {
                // exists
                ExistsExpression expression = (ExistsExpression) where;
                processWhereSubSelect(expression.getRightExpression(), whereSegment);
            } else if (where instanceof NotExpression) {
                // not exists
                NotExpression expression = (NotExpression) where;
                processWhereSubSelect(expression.getExpression(), whereSegment);
            } else if (where instanceof Parenthesis) {
                Parenthesis expression = (Parenthesis) where;
                processWhereSubSelect(expression.getExpression(), whereSegment);
            }
        }
    }

    protected void processSelectItem(SelectItem selectItem, final String whereSegment) {
        if (selectItem instanceof SelectExpressionItem) {
            SelectExpressionItem selectExpressionItem = (SelectExpressionItem) selectItem;
            final Expression expression = selectExpressionItem.getExpression();
            if (expression instanceof SubSelect) {
                processSelectBody(((SubSelect) expression).getSelectBody(), whereSegment);
            } else if (expression instanceof Function) {
                processFunction((Function) expression, whereSegment);
            }
        }
    }

    /**
     * 处理函数
     * <p>支持: 1. select fun(args..) 2. select fun1(fun2(args..),args..)<p>
     * <p> fixed gitee pulls/141</p>
     *
     * @param function
     */
    protected void processFunction(Function function, final String whereSegment) {
        ExpressionList parameters = function.getParameters();
        if (parameters != null) {
            parameters.getExpressions().forEach(expression -> {
                if (expression instanceof SubSelect) {
                    processSelectBody(((SubSelect) expression).getSelectBody(), whereSegment);
                } else if (expression instanceof Function) {
                    processFunction((Function) expression, whereSegment);
                }
            });
        }
    }

    /**
     * 处理子查询等
     */
    protected void processOtherFromItem(FromItem fromItem, final String whereSegment) {
        // 去除括号
        while (fromItem instanceof ParenthesisFromItem) {
            fromItem = ((ParenthesisFromItem) fromItem).getFromItem();
        }

        if (fromItem instanceof SubSelect) {
            SubSelect subSelect = (SubSelect) fromItem;
            if (subSelect.getSelectBody() != null) {
                processSelectBody(subSelect.getSelectBody(), whereSegment);
            }
        } else if (fromItem instanceof ValuesList) {
            logger.debug("Perform a subQuery, if you do not give us feedback");
        } else if (fromItem instanceof LateralSubSelect) {
            LateralSubSelect lateralSubSelect = (LateralSubSelect) fromItem;
            if (lateralSubSelect.getSubSelect() != null) {
                SubSelect subSelect = lateralSubSelect.getSubSelect();
                if (subSelect.getSelectBody() != null) {
                    processSelectBody(subSelect.getSelectBody(), whereSegment);
                }
            }
        }
    }

    /**
     * 处理 sub join
     *
     * @param subJoin subJoin
     * @return Table subJoin 中的主表
     */
    private List<Table> processSubJoin(SubJoin subJoin, final String whereSegment) {
        List<Table> mainTables = new ArrayList<>();
        if (subJoin.getJoinList() != null) {
            List<Table> list = processFromItem(subJoin.getLeft(), whereSegment);
            mainTables.addAll(list);
            mainTables = processJoins(mainTables, subJoin.getJoinList(), whereSegment);
        }
        return mainTables;
    }

    /**
     * 处理 joins
     *
     * @param mainTables 可以为 null
     * @param joins      join 集合
     * @return List<Table> 右连接查询的 Table 列表
     */
    private List<Table> processJoins(List<Table> mainTables, List<Join> joins, final String whereSegment) {
        // join 表达式中最终的主表
        Table mainTable = null;
        // 当前 join 的左表
        Table leftTable = null;

        if (mainTables == null) {
            mainTables = new ArrayList<>();
        } else if (mainTables.size() == 1) {
            mainTable = mainTables.get(0);
            leftTable = mainTable;
        }

        //对于 on 表达式写在最后的 join，需要记录下前面多个 on 的表名
        Deque<List<Table>> onTableDeque = new LinkedList<>();
        for (Join join : joins) {
            // 处理 on 表达式
            FromItem joinItem = join.getRightItem();

            // 获取当前 join 的表，subJoint 可以看作是一张表
            List<Table> joinTables = null;
            if (joinItem instanceof Table) {
                joinTables = new ArrayList<>();
                joinTables.add((Table) joinItem);
            } else if (joinItem instanceof SubJoin) {
                joinTables = processSubJoin((SubJoin) joinItem, whereSegment);
            }

            if (joinTables != null) {

                // 如果是隐式内连接
                if (join.isSimple()) {
                    mainTables.addAll(joinTables);
                    continue;
                }

                // 当前表是否忽略
                Table joinTable = joinTables.get(0);

                List<Table> onTables = null;
                // 如果不要忽略，且是右连接，则记录下当前表
                if (join.isRight()) {
                    mainTable = joinTable;
                    if (leftTable != null) {
                        onTables = Collections.singletonList(leftTable);
                    }
                } else if (join.isInner()) {
                    if (mainTable == null) {
                        onTables = Collections.singletonList(joinTable);
                    } else {
                        onTables = Arrays.asList(mainTable, joinTable);
                    }
                    mainTable = null;
                } else {
                    onTables = Collections.singletonList(joinTable);
                }

                mainTables = new ArrayList<>();
                if (mainTable != null) {
                    mainTables.add(mainTable);
                }

                // 获取 join 尾缀的 on 表达式列表
                Collection<Expression> originOnExpressions = join.getOnExpressions();
                // 正常 join on 表达式只有一个，立刻处理
                if (originOnExpressions.size() == 1 && onTables != null) {
                    List<Expression> onExpressions = new LinkedList<>();
                    onExpressions.add(builderExpression(originOnExpressions.iterator().next(), onTables, whereSegment));
                    join.setOnExpressions(onExpressions);
                    leftTable = joinTable;
                    continue;
                }
                // 表名压栈，忽略的表压入 null，以便后续不处理
                onTableDeque.push(onTables);
                // 尾缀多个 on 表达式的时候统一处理
                if (originOnExpressions.size() > 1) {
                    Collection<Expression> onExpressions = new LinkedList<>();
                    for (Expression originOnExpression : originOnExpressions) {
                        List<Table> currentTableList = onTableDeque.poll();
                        if (CollectionUtils.isEmpty(currentTableList)) {
                            onExpressions.add(originOnExpression);
                        } else {
                            onExpressions.add(builderExpression(originOnExpression, currentTableList, whereSegment));
                        }
                    }
                    join.setOnExpressions(onExpressions);
                }
                leftTable = joinTable;
            } else {
                processOtherFromItem(joinItem, whereSegment);
                leftTable = null;
            }
        }

        return mainTables;
    }

    /**
     * 处理条件
     */
    protected Expression builderExpression(Expression currentExpression, List<Table> tables, final String whereSegment) {
        // 没有表需要处理直接返回
        if (CollectionUtils.isEmpty(tables)) {
            return currentExpression;
        }
        // 构造每张表的条件
        List<Expression> expressions = tables.stream()
            .map(item -> buildTableExpression(item, whereSegment))
            .filter(Objects::nonNull)
            .collect(Collectors.toList());

        // 没有表需要处理直接返回
        if (CollectionUtils.isEmpty(expressions)) {
            return currentExpression;
        }

        // 注入的表达式
        Expression injectExpression = expressions.get(0);
        // 如果有多表，则用 and 连接
        if (expressions.size() > 1) {
            for (int i = 1; i < expressions.size(); i++) {
                injectExpression = new AndExpression(injectExpression, expressions.get(i));
            }
        }

        if (currentExpression == null) {
            return injectExpression;
        }
        if (currentExpression instanceof OrExpression) {
            return new AndExpression(new Parenthesis(currentExpression), injectExpression);
        } else {
            return new AndExpression(currentExpression, injectExpression);
        }
    }

    /**
     * 构建数据库表的查询条件
     *
     * @param table        表对象
     * @param whereSegment 所属Mapper对象全路径
     * @return 需要拼接的新条件（不会覆盖原有的where条件，只会在原有条件上再加条件），为 null 则不加入新的条件
     */
    public abstract Expression buildTableExpression(final Table table, final String whereSegment);
}
