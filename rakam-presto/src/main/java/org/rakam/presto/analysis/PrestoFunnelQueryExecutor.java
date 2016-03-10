/*
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
package org.rakam.presto.analysis;

import com.facebook.presto.sql.RakamSqlFormatter;
import com.facebook.presto.sql.RakamSqlFormatter.ExpressionFormatter;
import com.facebook.presto.sql.tree.DefaultExpressionTraversalVisitor;
import com.facebook.presto.sql.tree.Expression;
import com.facebook.presto.sql.tree.QualifiedNameReference;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.rakam.analysis.CalculatedUserSet;
import org.rakam.analysis.ContinuousQueryService;
import org.rakam.analysis.FunnelQueryExecutor;
import org.rakam.analysis.MaterializedViewService;
import org.rakam.report.DelegateQueryExecution;
import org.rakam.report.PreComputedTableSubQueryVisitor;
import org.rakam.report.QueryExecution;
import org.rakam.report.QueryExecutorService;
import org.rakam.util.RakamException;

import javax.inject.Inject;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static java.time.format.DateTimeFormatter.ISO_LOCAL_DATE;

public class PrestoFunnelQueryExecutor implements FunnelQueryExecutor {
    private final QueryExecutorService executor;
    private static final String CONNECTOR_FIELD = "_user";
    private final MaterializedViewService materializedViewService;
    private final ContinuousQueryService continuousQueryService;

    @Inject
    public PrestoFunnelQueryExecutor(QueryExecutorService executor, MaterializedViewService materializedViewService, ContinuousQueryService continuousQueryService) {
        this.materializedViewService = materializedViewService;
        this.continuousQueryService = continuousQueryService;
        this.executor = executor;
    }

    @Override
    public QueryExecution query(String project, List<FunnelQueryExecutor.FunnelStep> steps, Optional<String> dimension, LocalDate startDate, LocalDate endDate) {
        if (dimension.isPresent() && CONNECTOR_FIELD.equals(dimension.get())) {
            throw new RakamException("Dimension and connector field cannot be equal", HttpResponseStatus.BAD_REQUEST);
        }

        Set<CalculatedUserSet> calculatedUserSets = new HashSet<>();

        String stepQueries = IntStream.range(0, steps.size())
                .mapToObj(i -> convertFunnel(calculatedUserSets, project, CONNECTOR_FIELD, i, steps.get(i), dimension, startDate, endDate))
                .collect(Collectors.joining(", "));

        String query;
        if (dimension.isPresent()) {
            query = IntStream.range(0, steps.size())
                    .mapToObj(i -> String.format("(SELECT step, (CASE WHEN rank > 15 THEN 'Others' ELSE cast(dimension as varchar) END) as %s, sum(count) FROM (select 'Step %d' as step, dimension, count(distinct _user) count, row_number() OVER(ORDER BY 3 DESC) rank from step%s GROUP BY 2 ORDER BY 4 ASC) GROUP BY 1, 2 ORDER BY 3 DESC)",
                            dimension.get(), i + 1, i))
                    .collect(Collectors.joining(" UNION ALL "));
        } else {
            query = IntStream.range(0, steps.size())
                    .mapToObj(i -> String.format("(SELECT 'Step %d' as step, count(distinct %s) count FROM step%d)",
                            i + 1, CONNECTOR_FIELD, i))
                    .collect(Collectors.joining(" UNION ALL "));
        }

        return new DelegateQueryExecution(executor.executeQuery(project, "WITH \n" + stepQueries + " " + query + " ORDER BY 1 ASC"),
                result -> {
                    result.setProperty("calculatedUserSets", calculatedUserSets);
                    return result;
                });
    }

    private String convertFunnel(Set<CalculatedUserSet> calculatedUserSets, String project,
                                 String connectorField,
                                 int idx,
                                 FunnelStep funnelStep,
                                 Optional<String> dimension,
                                 LocalDate startDate, LocalDate endDate) {
        String timePredicate = String.format("BETWEEN cast('%s' as date) and cast('%s' as date) + interval '1' day",
                startDate.format(ISO_LOCAL_DATE), endDate.format(ISO_LOCAL_DATE));

        Optional<String> joinPreviousStep = idx == 0 ?
                Optional.empty() :
                Optional.of(String.format("JOIN step%d on (step%d.date >= step%d.date %s AND step%d.%s = step%d.%s)",
                        idx - 1, idx, idx - 1,
                        dimension.map(value -> String.format("AND step%d.dimension = step%d.dimension", idx, idx - 1)).orElse(""),
                        idx, connectorField, idx - 1, connectorField));

        Optional<String> preComputedTable = getPreComputedTable(calculatedUserSets, project, funnelStep.getCollection(), connectorField,
                joinPreviousStep, timePredicate, dimension, funnelStep.getExpression(), idx);

        if (preComputedTable.isPresent()) {
            return String.format("step%s AS (%s)", idx, preComputedTable.get());
        } else {
            Optional<String> filterExp = funnelStep.getExpression().map(value -> "AND " + RakamSqlFormatter.formatExpression(value,
                    name -> name.getParts().stream().map(ExpressionFormatter::formatIdentifier).collect(Collectors.joining(".")),
                    name -> funnelStep.getCollection() + "." + name.getParts().stream()
                            .map(ExpressionFormatter::formatIdentifier).collect(Collectors.joining("."))));

            return String.format("step%d AS (select step%d.* FROM (%s) step%d %s)",
                    idx, idx,
                    String.format("SELECT cast(_time as date) as date, %s %s from %s where _time %s %s group by 1, 2 %s",
                            dimension.map(value -> value + " as dimension,").orElse(""), connectorField,
                            funnelStep.getCollection(),
                            timePredicate, filterExp.orElse(""), dimension.map(value -> ", 3").orElse("")),
                    idx,
                    joinPreviousStep.orElse(""));
        }
    }

    private Optional<String> getPreComputedTable(Set<CalculatedUserSet> calculatedUserSets, String project, String collection, String connectorField,
                                                 Optional<String> joinPart,
                                                 String timePredicate, Optional<String> dimension,
                                                 Optional<Expression> filterExpression, int stepIdx) {
        String tableNameForCollection = connectorField + "s_daily_" + collection;

        if (filterExpression.isPresent()) {
            try {
                String query = filterExpression.get().accept(new PreComputedTableSubQueryVisitor(columnName -> {
                    String tableRef = tableNameForCollection + "_by_" + columnName;
                    if (continuousQueryService.list(project).stream().anyMatch(e -> e.tableName.equals(tableRef))) {
                        return Optional.of("continuous." + tableRef);
                    } else if (materializedViewService.list(project).stream().anyMatch(e -> e.tableName.equals(tableRef))) {
                        return Optional.of("materialized." + tableRef);
                    }

                    calculatedUserSets.add(new CalculatedUserSet(Optional.of(collection), Optional.of(columnName)));
                    return Optional.empty();
                }), false);


                if (dimension.isPresent()) {
                    final boolean[] referenced = {false};

                    filterExpression.get().accept(new DefaultExpressionTraversalVisitor<Void, Void>() {
                        @Override
                        protected Void visitQualifiedNameReference(QualifiedNameReference node, Void context) {
                            if (node.getName().toString().equals(dimension.get())) {
                                referenced[0] = true;
                            }
                            return null;
                        }
                    }, null);

                    if (referenced[0]) {
                        return Optional.of(query);
                    }

                    String tableName = tableNameForCollection + dimension.map(value -> "_by_" + value).orElse("");
                    Optional<String> schema = getSchemaForPreCalculatedTable(project, connectorField, tableName, dimension);

                    if (!schema.isPresent()) {
                        calculatedUserSets.add(new CalculatedUserSet(Optional.of(collection), dimension));
                        return Optional.empty();
                    }

                    return Optional.of("SELECT data.date, data.dimension, data._user FROM " + schema.get() + "." + tableName + " data " +
                            "JOIN (" + query + ") filter ON (filter.date = data.date AND filter._user = data._user)");
                } else {
                    return Optional.of(query);
                }
            } catch (Exception e) {
                return Optional.empty();
            }
        }

        String dimensionColumn = dimension.map(value -> "dimension").orElse("date");

        String refTable = dimension.map(value -> tableNameForCollection + "_by_" + value).orElse(tableNameForCollection);

        Optional<String> table = getSchemaForPreCalculatedTable(project, connectorField, collection, dimension);
        if (!table.isPresent()) {
            calculatedUserSets.add(new CalculatedUserSet(Optional.of(collection), dimension));
        }
        return table
                .map(value -> generatePreCalculatedTableSql(refTable, value, connectorField,
                        dimensionColumn, joinPart, timePredicate, stepIdx));
    }

    private Optional<String> getSchemaForPreCalculatedTable(String project, String connectorField, String collection, Optional<String> dimension) {
        String refTable = connectorField + "s_daily_" + collection + dimension.map(value -> collection + "_by_" + value).orElse("");

        if (continuousQueryService.list(project).stream().anyMatch(e -> e.tableName.equals(refTable))) {
            return Optional.of("continuous");
        } else if (materializedViewService.list(project).stream().anyMatch(e -> e.tableName.equals(refTable))) {
            return Optional.of("materialized");
        }

        return Optional.empty();
    }

    private String generatePreCalculatedTableSql(String table, String schema, String connectorField, String dimensionColumn, Optional<String> joinPart, String timePredicate, int stepIdx) {
        return String.format("select step%d.%s, step%d.%s from %s.%s as step%d %s where step%d.date %s",
                stepIdx, dimensionColumn, stepIdx, connectorField, schema, table, stepIdx, joinPart.orElse(""), stepIdx, timePredicate);
    }
}