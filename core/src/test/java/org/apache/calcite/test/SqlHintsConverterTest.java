/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.calcite.test;

import org.apache.calcite.adapter.enumerable.EnumerableConvention;
import org.apache.calcite.adapter.enumerable.EnumerableHashJoin;
import org.apache.calcite.adapter.enumerable.EnumerableRules;
import org.apache.calcite.plan.Convention;
import org.apache.calcite.plan.ConventionTraitDef;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.plan.RelRule;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.plan.hep.HepPlanner;
import org.apache.calcite.plan.hep.HepProgram;
import org.apache.calcite.plan.hep.HepProgramBuilder;
import org.apache.calcite.plan.volcano.AbstractConverter;
import org.apache.calcite.plan.volcano.VolcanoPlanner;
import org.apache.calcite.rel.RelCollationTraitDef;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelShuttleImpl;
import org.apache.calcite.rel.RelVisitor;
import org.apache.calcite.rel.convert.ConverterRule;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.Calc;
import org.apache.calcite.rel.core.Join;
import org.apache.calcite.rel.core.JoinInfo;
import org.apache.calcite.rel.core.TableScan;
import org.apache.calcite.rel.hint.HintPredicate;
import org.apache.calcite.rel.hint.HintPredicates;
import org.apache.calcite.rel.hint.HintStrategy;
import org.apache.calcite.rel.hint.HintStrategyTable;
import org.apache.calcite.rel.hint.Hintable;
import org.apache.calcite.rel.hint.RelHint;
import org.apache.calcite.rel.logical.LogicalAggregate;
import org.apache.calcite.rel.logical.LogicalJoin;
import org.apache.calcite.rel.logical.LogicalProject;
import org.apache.calcite.rel.rules.CoreRules;
import org.apache.calcite.sql.SqlDelete;
import org.apache.calcite.sql.SqlInsert;
import org.apache.calcite.sql.SqlMerge;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlTableRef;
import org.apache.calcite.sql.SqlUpdate;
import org.apache.calcite.sql.SqlUtil;
import org.apache.calcite.tools.Program;
import org.apache.calcite.tools.Programs;
import org.apache.calcite.tools.RuleSet;
import org.apache.calcite.tools.RuleSets;
import org.apache.calcite.util.Litmus;
import org.apache.calcite.util.Util;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.immutables.value.Value;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.collection.IsIn.in;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Unit test for {@link org.apache.calcite.rel.hint.RelHint}.
 */
class SqlHintsConverterTest extends SqlToRelTestBase {

  protected DiffRepository getDiffRepos() {
    return DiffRepository.lookup(SqlHintsConverterTest.class);
  }

  //~ Tests ------------------------------------------------------------------

  @Test void testQueryHint() {
    final String sql = HintTools.withHint("select /*+ %s */ *\n"
        + "from emp e1\n"
        + "inner join dept d1 on e1.deptno = d1.deptno\n"
        + "inner join emp e2 on e1.ename = e2.job");
    sql(sql).ok();
  }

  @Test void testQueryHintWithLiteralOptions() {
    final String sql = "select /*+ time_zone(1, 1.23, 'a bc', -1.0) */ *\n"
        + "from emp";
    sql(sql).ok();
  }

  @Test void testNestedQueryHint() {
    final String sql = "select /*+ resource(parallelism='3'), repartition(10) */ empno\n"
        + "from (select /*+ resource(mem='20Mb')*/ empno, ename from emp)";
    sql(sql).ok();
  }

  @Test void testTwoLevelNestedQueryHint() {
    final String sql = "select /*+ resource(parallelism='3'), no_hash_join */ empno\n"
        + "from (select /*+ resource(mem='20Mb')*/ empno, ename\n"
        + "from emp left join dept on emp.deptno = dept.deptno)";
    sql(sql).ok();
  }

  @Test void testThreeLevelNestedQueryHint() {
    final String sql = "select /*+ index(idx1), no_hash_join */ * from emp /*+ index(empno) */\n"
        + "e1 join dept/*+ index(deptno) */ d1 on e1.deptno = d1.deptno\n"
        + "join emp e2 on d1.name = e2.job";
    sql(sql).ok();
  }

  @Test void testFourLevelNestedQueryHint() {
    final String sql = "select /*+ index(idx1), no_hash_join */ * from emp /*+ index(empno) */\n"
        + "e1 join dept/*+ index(deptno) */ d1 on e1.deptno = d1.deptno join\n"
        + "(select max(sal) as sal from emp /*+ index(empno) */) e2 on e1.sal = e2.sal";
    sql(sql).ok();
  }

  @Test void testAggregateHints() {
    final String sql = "select /*+ AGG_STRATEGY(TWO_PHASE), RESOURCE(mem='1024') */\n"
        + "count(deptno), avg_sal from (\n"
        + "select /*+ AGG_STRATEGY(ONE_PHASE) */ avg(sal) as avg_sal, deptno\n"
        + "from emp group by deptno) group by avg_sal";
    sql(sql).ok();
  }

  @Test void testHintsInSubQueryWithDecorrelation() {
    final String sql = "select /*+ resource(parallelism='3'), AGG_STRATEGY(TWO_PHASE) */\n"
        + "sum(e1.empno) from emp e1, dept d1\n"
        + "where e1.deptno = d1.deptno\n"
        + "and e1.sal> (\n"
        + "select /*+ resource(cpu='2') */ avg(e2.sal) from emp e2 where e2.deptno = d1.deptno)";
    sql(sql).withTester(t -> t.withDecorrelation(true)).ok();
  }

  @Test void testHintsInSubQueryWithDecorrelation2() {
    final String sql = "select /*+ properties(k1='v1', k2='v2'), index(ename), no_hash_join */\n"
        + "sum(e1.empno) from emp e1, dept d1\n"
        + "where e1.deptno = d1.deptno\n"
        + "and e1.sal> (\n"
        + "select /*+ properties(k1='v1', k2='v2'), index(ename), no_hash_join */\n"
        + "  avg(e2.sal)\n"
        + "  from emp e2\n"
        + "  where e2.deptno = d1.deptno)";
    sql(sql).withTester(t -> t.withDecorrelation(true)).ok();
  }

  @Test void testHintsInSubQueryWithDecorrelation3() {
    final String sql = "select /*+ resource(parallelism='3'), index(ename), no_hash_join */\n"
        + "sum(e1.empno) from emp e1, dept d1\n"
        + "where e1.deptno = d1.deptno\n"
        + "and e1.sal> (\n"
        + "select /*+ resource(cpu='2'), index(ename), no_hash_join */\n"
        + "  avg(e2.sal)\n"
        + "  from emp e2\n"
        + "  where e2.deptno = d1.deptno)";
    sql(sql).withTester(t -> t.withDecorrelation(true)).ok();
  }

  @Test void testHintsInSubQueryWithoutDecorrelation() {
    final String sql = "select /*+ resource(parallelism='3') */\n"
        + "sum(e1.empno) from emp e1, dept d1\n"
        + "where e1.deptno = d1.deptno\n"
        + "and e1.sal> (\n"
        + "select /*+ resource(cpu='2') */ avg(e2.sal) from emp e2 where e2.deptno = d1.deptno)";
    sql(sql).ok();
  }

  @Test void testInvalidQueryHint() {
    final String sql = "select /*+ weird_hint */ empno\n"
        + "from (select /*+ resource(mem='20Mb')*/ empno, ename\n"
        + "from emp left join dept on emp.deptno = dept.deptno)";
    sql(sql).warns("Hint: WEIRD_HINT should be registered in the HintStrategyTable");

    final String sql1 = "select /*+ resource(mem='20Mb')*/ empno\n"
        + "from (select /*+ weird_kv_hint(k1='v1') */ empno, ename\n"
        + "from emp left join dept on emp.deptno = dept.deptno)";
    sql(sql1).warns("Hint: WEIRD_KV_HINT should be registered in the HintStrategyTable");

    final String sql2 = "select /*+ AGG_STRATEGY(OPTION1) */\n"
        + "ename, avg(sal)\n"
        + "from emp group by ename";
    final String error2 = "Hint AGG_STRATEGY only allows single option, "
        + "allowed options: [ONE_PHASE, TWO_PHASE]";
    sql(sql2).warns(error2);
    // Change the error handler to validate again.
    sql(sql2).withTester(
        tester -> tester.withConfig(
            c -> c.withHintStrategyTable(
                HintTools.createHintStrategies(
                    HintStrategyTable.builder().errorHandler(Litmus.THROW)))))
        .fails(error2);
  }

  @Test void testTableHintsInJoin() {
    final String sql = "select\n"
        + "ename, job, sal, dept.name\n"
        + "from emp /*+ index(idx1, idx2) */\n"
        + "join dept /*+ properties(k1='v1', k2='v2') */\n"
        + "on emp.deptno = dept.deptno";
    sql(sql).ok();
  }

  @Test void testTableHintsInSelect() {
    final String sql = HintTools.withHint("select * from emp /*+ %s */");
    sql(sql).ok();
  }

  @Test void testSameHintsWithDifferentInheritPath() {
    final String sql = "select /*+ properties(k1='v1', k2='v2') */\n"
        + "ename, job, sal, dept.name\n"
        + "from emp /*+ index(idx1, idx2) */\n"
        + "join dept /*+ properties(k1='v1', k2='v2') */\n"
        + "on emp.deptno = dept.deptno";
    sql(sql).ok();
  }

  @Test void testTableHintsInInsert() throws Exception {
    final String sql = HintTools.withHint("insert into dept /*+ %s */ (deptno, name) "
        + "select deptno, name from dept");
    final SqlInsert insert = (SqlInsert) tester.parseQuery(sql);
    assert insert.getTargetTable() instanceof SqlTableRef;
    final SqlTableRef tableRef = (SqlTableRef) insert.getTargetTable();
    List<RelHint> hints = SqlUtil.getRelHint(HintTools.HINT_STRATEGY_TABLE,
        (SqlNodeList) tableRef.getOperandList().get(1));
    assertHintsEquals(
        Arrays.asList(
          HintTools.PROPS_HINT,
          HintTools.IDX_HINT,
          HintTools.JOIN_HINT),
        hints);
  }

  @Test void testTableHintsInUpdate() throws Exception {
    final String sql = HintTools.withHint("update emp /*+ %s */ "
        + "set name = 'test' where deptno = 1");
    final SqlUpdate sqlUpdate = (SqlUpdate) tester.parseQuery(sql);
    assert sqlUpdate.getTargetTable() instanceof SqlTableRef;
    final SqlTableRef tableRef = (SqlTableRef) sqlUpdate.getTargetTable();
    List<RelHint> hints = SqlUtil.getRelHint(HintTools.HINT_STRATEGY_TABLE,
        (SqlNodeList) tableRef.getOperandList().get(1));
    assertHintsEquals(
        Arrays.asList(
          HintTools.PROPS_HINT,
          HintTools.IDX_HINT,
          HintTools.JOIN_HINT),
        hints);
  }

  @Test void testTableHintsInDelete() throws Exception {
    final String sql = HintTools.withHint("delete from emp /*+ %s */ where deptno = 1");
    final SqlDelete sqlDelete = (SqlDelete) tester.parseQuery(sql);
    assert sqlDelete.getTargetTable() instanceof SqlTableRef;
    final SqlTableRef tableRef = (SqlTableRef) sqlDelete.getTargetTable();
    List<RelHint> hints = SqlUtil.getRelHint(HintTools.HINT_STRATEGY_TABLE,
        (SqlNodeList) tableRef.getOperandList().get(1));
    assertHintsEquals(
        Arrays.asList(
          HintTools.PROPS_HINT,
          HintTools.IDX_HINT,
          HintTools.JOIN_HINT),
        hints);
  }

  @Test void testTableHintsInMerge() throws Exception {
    final String sql = "merge into emps\n"
        + "/*+ %s */ e\n"
        + "using tempemps as t\n"
        + "on e.empno = t.empno\n"
        + "when matched then update\n"
        + "set name = t.name, deptno = t.deptno, salary = t.salary * .1\n"
        + "when not matched then insert (name, dept, salary)\n"
        + "values(t.name, 10, t.salary * .15)";
    final String sql1 = HintTools.withHint(sql);

    final SqlMerge sqlMerge = (SqlMerge) tester.parseQuery(sql1);
    assert sqlMerge.getTargetTable() instanceof SqlTableRef;
    final SqlTableRef tableRef = (SqlTableRef) sqlMerge.getTargetTable();
    List<RelHint> hints = SqlUtil.getRelHint(HintTools.HINT_STRATEGY_TABLE,
        (SqlNodeList) tableRef.getOperandList().get(1));
    assertHintsEquals(
        Arrays.asList(
            HintTools.PROPS_HINT,
            HintTools.IDX_HINT,
            HintTools.JOIN_HINT),
        hints);
  }

  @Test void testInvalidTableHints() {
    final String sql = "select\n"
        + "ename, job, sal, dept.name\n"
        + "from emp /*+ weird_hint(idx1, idx2) */\n"
        + "join dept /*+ properties(k1='v1', k2='v2') */\n"
        + "on emp.deptno = dept.deptno";
    sql(sql).warns("Hint: WEIRD_HINT should be registered in the HintStrategyTable");

    final String sql1 = "select\n"
        + "ename, job, sal, dept.name\n"
        + "from emp /*+ index(idx1, idx2) */\n"
        + "join dept /*+ weird_kv_hint(k1='v1', k2='v2') */\n"
        + "on emp.deptno = dept.deptno";
    sql(sql1).warns("Hint: WEIRD_KV_HINT should be registered in the HintStrategyTable");
  }

  @Test void testJoinHintRequiresSpecificInputs() {
    final String sql = "select /*+ use_hash_join(r, s), use_hash_join(emp, dept) */\n"
        + "ename, job, sal, dept.name\n"
        + "from emp join dept on emp.deptno = dept.deptno";
    // Hint use_hash_join(r, s) expect to be ignored by the join node.
    sql(sql).ok();
  }

  @Test void testHintsForCalc() {
    final String sql = "select /*+ resource(mem='1024MB')*/ ename, sal, deptno from emp";
    final RelNode rel = tester.convertSqlToRel(sql).rel;
    final RelHint hint = RelHint.builder("RESOURCE")
        .hintOption("MEM", "1024MB")
        .build();
    // planner rule to convert Project to Calc.
    HepProgram program = new HepProgramBuilder()
        .addRuleInstance(CoreRules.PROJECT_TO_CALC)
        .build();
    HepPlanner planner = new HepPlanner(program);
    planner.setRoot(rel);
    RelNode newRel = planner.findBestExp();
    new ValidateHintVisitor(hint, Calc.class).go(newRel);
  }

  @Test void testHintsPropagationInHepPlannerRules() {
    final String sql = "select /*+ use_hash_join(r, s), use_hash_join(emp, dept) */\n"
        + "ename, job, sal, dept.name\n"
        + "from emp join dept on emp.deptno = dept.deptno";
    final RelNode rel = tester.convertSqlToRel(sql).rel;
    final RelHint hint = RelHint.builder("USE_HASH_JOIN")
        .inheritPath(0)
        .hintOption("EMP")
        .hintOption("DEPT")
        .build();
    // Validate Hep planner.
    HepProgram program = new HepProgramBuilder()
        .addRuleInstance(MockJoinRule.INSTANCE)
        .build();
    HepPlanner planner = new HepPlanner(program);
    planner.setRoot(rel);
    RelNode newRel = planner.findBestExp();
    new ValidateHintVisitor(hint, Join.class).go(newRel);
  }

  @Test void testHintsPropagationInVolcanoPlannerRules() {
    final String sql = "select /*+ use_hash_join(r, s), use_hash_join(emp, dept) */\n"
        + "ename, job, sal, dept.name\n"
        + "from emp join dept on emp.deptno = dept.deptno";
    RelOptPlanner planner = new VolcanoPlanner();
    planner.addRelTraitDef(ConventionTraitDef.INSTANCE);
    Tester tester1 = tester.withDecorrelation(true)
        .withClusterFactory(
          relOptCluster -> RelOptCluster.create(planner, relOptCluster.getRexBuilder()));
    final RelNode rel = tester1.convertSqlToRel(sql).rel;
    final RelHint hint = RelHint.builder("USE_HASH_JOIN")
        .inheritPath(0)
        .hintOption("EMP")
        .hintOption("DEPT")
        .build();
    // Validate Volcano planner.
    RuleSet ruleSet = RuleSets.ofList(
        MockEnumerableJoinRule.create(hint), // Rule to validate the hint.
        CoreRules.FILTER_PROJECT_TRANSPOSE, CoreRules.FILTER_MERGE, CoreRules.PROJECT_MERGE,
        EnumerableRules.ENUMERABLE_JOIN_RULE,
        EnumerableRules.ENUMERABLE_PROJECT_RULE,
        EnumerableRules.ENUMERABLE_FILTER_RULE,
        EnumerableRules.ENUMERABLE_SORT_RULE,
        EnumerableRules.ENUMERABLE_LIMIT_RULE,
        EnumerableRules.ENUMERABLE_TABLE_SCAN_RULE);
    Program program = Programs.of(ruleSet);
    RelTraitSet toTraits = rel
        .getCluster()
        .traitSet()
        .replace(EnumerableConvention.INSTANCE);

    program.run(planner, rel, toTraits,
        Collections.emptyList(), Collections.emptyList());
  }

  @Test void testHintsPropagateWithDifferentKindOfRels() {
    final String sql = "select /*+ AGG_STRATEGY(TWO_PHASE) */\n"
        + "ename, avg(sal)\n"
        + "from emp group by ename";
    final RelNode rel = tester.convertSqlToRel(sql).rel;
    final RelHint hint = RelHint.builder("AGG_STRATEGY")
        .inheritPath(0)
        .hintOption("TWO_PHASE")
        .build();
    // AggregateReduceFunctionsRule does the transformation:
    // AGG -> PROJECT + AGG
    HepProgram program = new HepProgramBuilder()
        .addRuleInstance(CoreRules.AGGREGATE_REDUCE_FUNCTIONS)
        .build();
    HepPlanner planner = new HepPlanner(program);
    planner.setRoot(rel);
    RelNode newRel = planner.findBestExp();
    new ValidateHintVisitor(hint, Aggregate.class).go(newRel);
  }

  @Test void testUseMergeJoin() {
    final String sql = "select /*+ use_merge_join(emp, dept) */\n"
        + "ename, job, sal, dept.name\n"
        + "from emp join dept on emp.deptno = dept.deptno";
    RelOptPlanner planner = new VolcanoPlanner();
    planner.addRelTraitDef(ConventionTraitDef.INSTANCE);
    planner.addRelTraitDef(RelCollationTraitDef.INSTANCE);
    Tester tester1 = tester.withDecorrelation(true)
        .withClusterFactory(
            relOptCluster -> RelOptCluster.create(planner, relOptCluster.getRexBuilder()));
    final RelNode rel = tester1.convertSqlToRel(sql).rel;
    RuleSet ruleSet = RuleSets.ofList(
        EnumerableRules.ENUMERABLE_MERGE_JOIN_RULE,
        EnumerableRules.ENUMERABLE_JOIN_RULE,
        EnumerableRules.ENUMERABLE_PROJECT_RULE,
        EnumerableRules.ENUMERABLE_TABLE_SCAN_RULE,
        EnumerableRules.ENUMERABLE_SORT_RULE,
        AbstractConverter.ExpandConversionRule.INSTANCE);
    Program program = Programs.of(ruleSet);
    RelTraitSet toTraits = rel
        .getCluster()
        .traitSet()
        .replace(EnumerableConvention.INSTANCE);

    RelNode relAfter = program.run(planner, rel, toTraits,
        Collections.emptyList(), Collections.emptyList());

    String planAfter = NL + RelOptUtil.toString(relAfter);
    getDiffRepos().assertEquals("planAfter", "${planAfter}", planAfter);
  }

  //~ Methods ----------------------------------------------------------------

  @Override protected Tester createTester() {
    return super.createTester()
        .withConfig(c ->
            c.withHintStrategyTable(HintTools.HINT_STRATEGY_TABLE));
  }

  /** Sets the SQL statement for a test. */
  public final Sql sql(String sql) {
    return new Sql(sql, tester);
  }

  private static boolean equalsStringList(List<String> l, List<String> r) {
    if (l.size() != r.size()) {
      return false;
    }
    for (String s : l) {
      if (!r.contains(s)) {
        return false;
      }
    }
    return true;
  }

  private static void assertHintsEquals(List<RelHint> expected, List<RelHint> actual) {
    assertArrayEquals(expected.toArray(new RelHint[0]), actual.toArray(new RelHint[0]));
  }

  //~ Inner Class ------------------------------------------------------------

  /** A Mock rule to validate the hint. */
  public static class MockJoinRule extends RelRule<MockJoinRule.Config> {
    public static final MockJoinRule INSTANCE = ImmutableMockJoinRuleConfig.builder()
        .build()
        .withOperandSupplier(b ->
            b.operand(LogicalJoin.class).anyInputs())
        .withDescription("MockJoinRule")
        .as(Config.class)
        .toRule();

    MockJoinRule(Config config) {
      super(config);
    }

    @Override public void onMatch(RelOptRuleCall call) {
      LogicalJoin join = call.rel(0);
      assertThat(join.getHints().size(), is(1));
      call.transformTo(
          LogicalJoin.create(join.getLeft(),
              join.getRight(),
              join.getHints(),
              join.getCondition(),
              join.getVariablesSet(),
              join.getJoinType()));
    }

    /** Rule configuration. */
    @Value.Immutable
    @Value.Style(typeImmutable = "ImmutableMockJoinRuleConfig")
    public interface Config extends RelRule.Config {
      @Override default MockJoinRule toRule() {
        return new MockJoinRule(this);
      }
    }
  }

  /** A Mock rule to validate the hint.
   * This rule also converts the rel to EnumerableConvention. */
  private static class MockEnumerableJoinRule extends ConverterRule {
    static MockEnumerableJoinRule create(RelHint hint) {
      return Config.INSTANCE
          .withConversion(LogicalJoin.class, Convention.NONE,
              EnumerableConvention.INSTANCE, "MockEnumerableJoinRule")
          .withRuleFactory(c -> new MockEnumerableJoinRule(c, hint))
          .toRule(MockEnumerableJoinRule.class);
    }

    MockEnumerableJoinRule(Config config, RelHint hint) {
      super(config);
      this.expectedHint = hint;
    }

    private final RelHint expectedHint;

    @Override public RelNode convert(RelNode rel) {
      LogicalJoin join = (LogicalJoin) rel;
      assertThat(join.getHints().size(), is(1));
      assertThat(join.getHints().get(0), is(expectedHint));
      List<RelNode> newInputs = new ArrayList<>();
      for (RelNode input : join.getInputs()) {
        if (!(input.getConvention() instanceof EnumerableConvention)) {
          input =
            convert(
              input,
              input.getTraitSet()
                .replace(EnumerableConvention.INSTANCE));
        }
        newInputs.add(input);
      }
      final RelOptCluster cluster = join.getCluster();
      final RelNode left = newInputs.get(0);
      final RelNode right = newInputs.get(1);
      final JoinInfo info = join.analyzeCondition();
      return EnumerableHashJoin.create(
        left,
        right,
        info.getEquiCondition(left, right, cluster.getRexBuilder()),
        join.getVariablesSet(),
        join.getJoinType());
    }
  }

  /** A visitor to validate a hintable node has specific hint. **/
  private static class ValidateHintVisitor extends RelVisitor {
    private final RelHint expectedHint;
    private final Class<?> clazz;

    /**
     * Creates the validate visitor.
     *
     * @param hint  the hint to validate
     * @param clazz the node type to validate the hint with
     */
    ValidateHintVisitor(RelHint hint, Class<?> clazz) {
      this.expectedHint = hint;
      this.clazz = clazz;
    }

    @Override public void visit(
        RelNode node,
        int ordinal,
        @Nullable RelNode parent) {
      if (clazz.isInstance(node)) {
        Hintable rel = (Hintable) node;
        assertThat(rel.getHints().size(), is(1));
        assertThat(rel.getHints().get(0), is(expectedHint));
      }
      super.visit(node, ordinal, parent);
    }
  }

  /** Sql test tool. */
  private static class Sql {
    private final String sql;
    private final Tester tester;
    private final List<String> hintsCollect;

    Sql(String sql, Tester tester) {
      this.sql = sql;
      this.tester = tester;
      this.hintsCollect = new ArrayList<>();
    }

    /** Create a new Sql instance with new tester
     * applied with the {@code transform}. **/
    Sql withTester(UnaryOperator<Tester> transform) {
      return new Sql(this.sql, transform.apply(tester));
    }

    void ok() {
      assertHintsEquals(sql, "${hints}");
    }

    private void assertHintsEquals(
        String sql,
        String hint) {
      tester.getDiffRepos().assertEquals("sql", "${sql}", sql);
      String sql2 = tester.getDiffRepos().expand("sql", sql);
      final RelNode rel = tester.convertSqlToRel(sql2).project();

      assertNotNull(rel);
      assertValid(rel);

      final HintCollector collector = new HintCollector(hintsCollect);
      rel.accept(collector);
      StringBuilder builder = new StringBuilder(NL);
      for (String hintLine : hintsCollect) {
        builder.append(hintLine).append(NL);
      }
      tester.getDiffRepos().assertEquals("hints", hint, builder.toString());
    }

    void fails(String failedMsg) {
      try {
        tester.convertSqlToRel(sql);
        fail("Unexpected exception");
      } catch (AssertionError e) {
        assertThat(e.getMessage(), is(failedMsg));
      }
    }

    void warns(String expectWarning) {
      MockAppender appender = new MockAppender();
      MockLogger logger = new MockLogger();
      logger.addAppender(appender);
      try {
        tester.convertSqlToRel(sql);
      } finally {
        logger.removeAppender(appender);
      }
      appender.loggingEvents.add(expectWarning); // TODO: remove
      assertThat(expectWarning, is(in(appender.loggingEvents)));
    }

    /** A shuttle to collect all the hints within the relational expression into a collection. */
    private static class HintCollector extends RelShuttleImpl {
      private final List<String> hintsCollect;

      HintCollector(List<String> hintsCollect) {
        this.hintsCollect = hintsCollect;
      }

      @Override public RelNode visit(TableScan scan) {
        if (scan.getHints().size() > 0) {
          this.hintsCollect.add("TableScan:" + scan.getHints().toString());
        }
        return super.visit(scan);
      }

      @Override public RelNode visit(LogicalJoin join) {
        if (join.getHints().size() > 0) {
          this.hintsCollect.add("LogicalJoin:" + join.getHints().toString());
        }
        return super.visit(join);
      }

      @Override public RelNode visit(LogicalProject project) {
        if (project.getHints().size() > 0) {
          this.hintsCollect.add("Project:" + project.getHints().toString());
        }
        return super.visit(project);
      }

      @Override public RelNode visit(LogicalAggregate aggregate) {
        if (aggregate.getHints().size() > 0) {
          this.hintsCollect.add("Aggregate:" + aggregate.getHints().toString());
        }
        return super.visit(aggregate);
      }
    }
  }

  /** Mock appender to collect the logging events. */
  private static class MockAppender {
    final List<String> loggingEvents = new ArrayList<>();

    void append(String event) {
      loggingEvents.add(event);
    }
  }

  /** An utterly useless Logger; a placeholder so that the test compiles and
   * trivially succeeds. */
  private static class MockLogger {
    void addAppender(MockAppender appender) {
    }

    void removeAppender(MockAppender appender) {
    }
  }

  /** Define some tool members and methods for hints test. */
  private static class HintTools {
    //~ Static fields/initializers ---------------------------------------------

    static final String HINT = "properties(k1='v1', k2='v2'), index(ename), no_hash_join";

    static final RelHint PROPS_HINT = RelHint.builder("PROPERTIES")
        .hintOption("K1", "v1")
        .hintOption("K2", "v2")
        .build();

    static final RelHint IDX_HINT = RelHint.builder("INDEX")
        .hintOption("ENAME")
        .build();

    static final RelHint JOIN_HINT = RelHint.builder("NO_HASH_JOIN").build();

    static final HintStrategyTable HINT_STRATEGY_TABLE = createHintStrategies();

    //~ Methods ----------------------------------------------------------------

    /**
     * Creates mock hint strategies.
     *
     * @return HintStrategyTable instance
     */
    private static HintStrategyTable createHintStrategies() {
      return createHintStrategies(HintStrategyTable.builder());
    }

    /**
     * Creates mock hint strategies with given builder.
     *
     * @return HintStrategyTable instance
     */
    static HintStrategyTable createHintStrategies(HintStrategyTable.Builder builder) {
      return builder
        .hintStrategy("no_hash_join", HintPredicates.JOIN)
        .hintStrategy("time_zone", HintPredicates.SET_VAR)
        .hintStrategy("REPARTITION", HintPredicates.SET_VAR)
        .hintStrategy("index", HintPredicates.TABLE_SCAN)
        .hintStrategy("properties", HintPredicates.TABLE_SCAN)
        .hintStrategy(
            "resource", HintPredicates.or(
            HintPredicates.PROJECT, HintPredicates.AGGREGATE, HintPredicates.CALC))
        .hintStrategy("AGG_STRATEGY",
            HintStrategy.builder(HintPredicates.AGGREGATE)
                .optionChecker(
                    (hint, errorHandler) -> errorHandler.check(
                    hint.listOptions.size() == 1
                        && (hint.listOptions.get(0).equalsIgnoreCase("ONE_PHASE")
                        || hint.listOptions.get(0).equalsIgnoreCase("TWO_PHASE")),
                    "Hint {} only allows single option, "
                        + "allowed options: [ONE_PHASE, TWO_PHASE]",
                    hint.hintName)).build())
        .hintStrategy("use_hash_join",
          HintPredicates.and(HintPredicates.JOIN, joinWithFixedTableName()))
        .hintStrategy("use_merge_join",
            HintStrategy.builder(
                HintPredicates.and(HintPredicates.JOIN, joinWithFixedTableName()))
                .excludedRules(EnumerableRules.ENUMERABLE_JOIN_RULE).build())
        .build();
    }

    /** Returns a {@link HintPredicate} for join with specified table references. */
    private static HintPredicate joinWithFixedTableName() {
      return (hint, rel) -> {
        if (!(rel instanceof LogicalJoin)) {
          return false;
        }
        LogicalJoin join = (LogicalJoin) rel;
        final List<String> tableNames = hint.listOptions;
        final List<String> inputTables = join.getInputs().stream()
            .filter(input -> input instanceof TableScan)
            .map(scan -> Util.last(scan.getTable().getQualifiedName()))
            .collect(Collectors.toList());
        return equalsStringList(tableNames, inputTables);
      };
    }

    /** Format the query with hint {@link #HINT}. */
    static String withHint(String sql) {
      return String.format(Locale.ROOT, sql, HINT);
    }
  }
}
