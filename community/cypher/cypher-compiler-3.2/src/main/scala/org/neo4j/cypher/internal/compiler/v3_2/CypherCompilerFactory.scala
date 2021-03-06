/*
 * Copyright (c) 2002-2017 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.compiler.v3_2

import java.time.Clock

import org.neo4j.cypher.internal.compiler.v3_2.codegen.{CodeGenConfiguration, CodeGenMode}
import org.neo4j.cypher.internal.compiler.v3_2.codegen.spi.CodeStructure
import org.neo4j.cypher.internal.compiler.v3_2.executionplan._
import org.neo4j.cypher.internal.compiler.v3_2.helpers.RuntimeTypeConverter
import org.neo4j.cypher.internal.compiler.v3_2.planner.logical._
import org.neo4j.cypher.internal.compiler.v3_2.planner.logical.idp._
import org.neo4j.cypher.internal.compiler.v3_2.tracing.rewriters.RewriterStepSequencer
import org.neo4j.cypher.internal.frontend.v3_2.ast.Statement

object CypherCompilerFactory {
  val monitorTag = "cypher3.2"

  def costBasedCompiler(config: CypherCompilerConfiguration,
                        clock: Clock,
                        structure: CodeStructure[GeneratedQuery],
                        monitors: Monitors, logger: InfoLogger,
                        rewriterSequencer: (String) => RewriterStepSequencer,
                        plannerName: Option[CostBasedPlannerName],
                        runtimeName: Option[RuntimeName],
                        codeGenMode: Option[CodeGenMode],
                        updateStrategy: Option[UpdateStrategy],
                        typeConverter: RuntimeTypeConverter): CypherCompiler = {
    val rewriter = new ASTRewriter(rewriterSequencer)
    val metricsFactory = CachedMetricsFactory(SimpleMetricsFactory)

    // Pick runtime based on input
    val runtimePipeline = RuntimeBuilder.create(runtimeName, config.useErrorsOverWarnings)

    val planner = plannerName.getOrElse(CostBasedPlannerName.default)
    val queryGraphSolver = createQueryGraphSolver(planner, monitors, config)

    val actualUpdateStrategy: UpdateStrategy = updateStrategy.getOrElse(defaultUpdateStrategy)

    val createFingerprintReference: (Option[PlanFingerprint]) => PlanFingerprintReference =
      new PlanFingerprintReference(clock, config.queryPlanTTL, config.statsDivergenceThreshold, _)
    val planCacheFactory = () => new LFUCache[Statement, ExecutionPlan](config.queryCacheSize)
    monitors.addMonitorListener(logStalePlanRemovalMonitor(logger), monitorTag)
    val cacheMonitor = monitors.newMonitor[AstCacheMonitor](monitorTag)
    val cache = new MonitoringCacheAccessor[Statement, ExecutionPlan](cacheMonitor)

    val actualCodeGenMode = codeGenMode.getOrElse(CodeGenMode.default)
    val codeGenConfiguration = CodeGenConfiguration(mode = actualCodeGenMode)

    CypherCompiler(runtimePipeline, rewriter, cache, planCacheFactory, cacheMonitor, monitors, rewriterSequencer,
      createFingerprintReference, typeConverter, metricsFactory, queryGraphSolver, config, actualUpdateStrategy,
      codeGenConfiguration, clock, structure)
  }

  def createQueryGraphSolver(n: CostBasedPlannerName, monitors: Monitors, config: CypherCompilerConfiguration): QueryGraphSolver = n match {
    case IDPPlannerName =>
      val monitor = monitors.newMonitor[IDPQueryGraphSolverMonitor]()
      val solverConfig = new ConfigurableIDPSolverConfig(
        maxTableSize = config.idpMaxTableSize,
        iterationDurationLimit = config.idpIterationDuration
      )
      val singleComponentPlanner = SingleComponentPlanner(monitor, solverConfig)
      IDPQueryGraphSolver(singleComponentPlanner, cartesianProductsOrValueJoins, monitor)

    case DPPlannerName =>
      val monitor = monitors.newMonitor[IDPQueryGraphSolverMonitor]()
      val singleComponentPlanner = SingleComponentPlanner(monitor, DPSolverConfig)
      IDPQueryGraphSolver(singleComponentPlanner, cartesianProductsOrValueJoins, monitor)
  }


  private def logStalePlanRemovalMonitor(log: InfoLogger) = new AstCacheMonitor {
    override def cacheDiscard(key: Statement, userKey: String) {
      log.info(s"Discarded stale query from the query cache: $userKey")
    }
  }
}
