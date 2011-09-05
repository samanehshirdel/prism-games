//==============================================================================
//	
//	Copyright (c) 2002-
//	Authors:
//	* Dave Parker <david.parker@comlab.ox.ac.uk> (University of Oxford)
//	
//------------------------------------------------------------------------------
//	
//	This file is part of PRISM.
//	
//	PRISM is free software; you can redistribute it and/or modify
//	it under the terms of the GNU General Public License as published by
//	the Free Software Foundation; either version 2 of the License, or
//	(at your option) any later version.
//	
//	PRISM is distributed in the hope that it will be useful,
//	but WITHOUT ANY WARRANTY; without even the implied warranty of
//	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
//	GNU General Public License for more details.
//	
//	You should have received a copy of the GNU General Public License
//	along with PRISM; if not, write to the Free Software Foundation,
//	Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
//	
//==============================================================================

package explicit;

import java.util.BitSet;
import java.util.Set;

import parser.ast.Expression;
import parser.ast.ExpressionPATL;
import parser.ast.ExpressionProb;
import parser.ast.ExpressionReward;
import parser.ast.ExpressionSS;
import parser.ast.RewardStruct;
import prism.ModelType;
import prism.PrismException;
import explicit.rewards.ConstructRewards;
import explicit.rewards.MCRewards;
import explicit.rewards.MDPRewards;
import explicit.rewards.SMGRewards;
import explicit.rewards.STPGRewards;

/**
 * Super class for explicit-state probabilistic model checkers
 */
public class ProbModelChecker extends StateModelChecker
{
	// Model checking functions

	@Override
	public Object checkExpression(Model model, Expression expr) throws PrismException
	{
		Object res;

		// P operator
		if (expr instanceof ExpressionProb) {
			res = checkExpressionProb(model, (ExpressionProb) expr);
		}
		// R operator
		else if (expr instanceof ExpressionReward) {
			res = checkExpressionReward(model, (ExpressionReward) expr);
		}
		// S operator
		else if (expr instanceof ExpressionSS) {
			throw new PrismException("Explicit engine does not yet handle the S operator");
		}
		else if (expr instanceof ExpressionPATL)
		{
			res = checkExpressionPATL(model, (ExpressionPATL) expr);
		}
		// Otherwise, use the superclass
		else {
			res = super.checkExpression(model, expr);
		}

		return res;
	}

	private Object checkExpressionPATL(Model model, ExpressionPATL expr) throws PrismException
	{
		
		int type = expr.getExpressionType();
		ExpressionProb exprProb = expr.getExpressionProb();
		ExpressionReward exprRew = expr.getExpressionRew();
		Set<Integer> coalition = expr.getCoalition();
		
		String relOp;
		boolean min;
		StateValues probs = null;
		
		// get info about operator
		switch(type)
		{
		case ExpressionPATL.PRB:
			relOp = exprProb.getRelOp();
			break;
		case ExpressionPATL.REW:
			relOp = exprRew.getRelOp();
			break;
		default:
			throw new PrismException("Relational operator type unknown.");
		}
		
		// determine whether the problem is minimisation or maximisation
		if (relOp.equals(">") || relOp.equals(">=") || relOp.equals("min=")) {
			// min
			min = true;
		} else if (relOp.equals("<") || relOp.equals("<=") || relOp.equals("max=")) {
			// max
			min = false;
		} else {
			throw new PrismException("Can't use \"P=?\" for nondeterministic models; use \"Pmin=?\" or \"Pmax=?\"");
		}

		if(type == ExpressionPATL.PRB)
		{
			probs = ((SMGModelChecker) this).checkProbPathFormula(model, expr, min);
			
			// Print out probabilities
			if (getVerbosity() > 5) {
				mainLog.print("\nProbabilities (non-zero only) for all states:\n");
				mainLog.print(probs);
			}

			// For =? properties, just return values
			return probs;
			
		}
		else if (type == ExpressionPATL.REW)
		{
			Object rs; // Reward struct index
			RewardStruct rewStruct = null; // Reward struct object
			Expression rb; // Reward bound (expression)
			double r = 0; // Reward bound (actual value)
			
			StateValues rews = null;
			SMGRewards smgRewards = null;
			
			int i;
			
			// Get info from reward operator
			rs = expr.getExpressionRew().getRewardStructIndex();
			rb = expr.getExpressionRew().getReward();
			if (rb != null) {
				r = rb.evaluateDouble(constantValues);
				if (r < 0)
					throw new PrismException("Invalid reward bound " + r + " in R[] formula");
			}

			// Check for unhandled cases
			if (expr.getExpressionRew().getReward() != null)
				throw new PrismException("Explicit engine does not yet handle bounded R operators");
			// More? TODO
			
			// Get reward info
			if (modulesFile == null)
				throw new PrismException("No model file to obtain reward structures");
			if (modulesFile.getNumRewardStructs() == 0)
				throw new PrismException("Model has no rewards specified");
			if (rs == null) {
				rewStruct = modulesFile.getRewardStruct(0);
			} else if (rs instanceof Expression) {
				i = ((Expression) rs).evaluateInt(constantValues);
				rs = new Integer(i); // for better error reporting below
				rewStruct = modulesFile.getRewardStruct(i - 1);
			} else if (rs instanceof String) {
				rewStruct = modulesFile.getRewardStructByName((String) rs);
			}
			if (rewStruct == null)
				throw new PrismException("Invalid reward structure index \"" + rs + "\"");
			
			// Build rewards
			ConstructRewards constructRewards = new ConstructRewards(mainLog);
			smgRewards = constructRewards.buildSMGRewardStructure((SMG)model, rewStruct, constantValues);
			// Compute rewards
			mainLog.println("Building reward structure...");
			rews = ((SMGModelChecker) this).checkRewardFormula(model, smgRewards, expr, min);
			
			// Print out probabilities
			if (getVerbosity() > 5) {
				mainLog.print("\nProbabilities (non-zero only) for all states:\n");
				mainLog.print(rews);
			}

			// For =? properties, just return values
			return rews;
			
		}
		else
		{
			throw new PrismException("Expression type unknown.");
		}
	}

	/**
	 * Model check a P operator expression and return the values for all states.
	 */
	protected Object checkExpressionProb(Model model, ExpressionProb expr) throws PrismException
	{
		// Probability bound
		Expression pb; // (expression)
		double p = 0; // (actual value)
		// Relational operator
		String relOp;
		// For nondeterministic models, are we finding min (true) or max (false) probs
		boolean min1 = false;
		boolean min2 = false;
		ModelType modelType = model.getModelType();

		StateValues probs = null;

		// Get info from prob operator
		relOp = expr.getRelOp();
		pb = expr.getProb();
		if (pb != null) {
			p = pb.evaluateDouble(constantValues);
			if (p < 0 || p > 1)
				throw new PrismException("Invalid probability bound " + p + " in P operator");
		}

		// For nondeterministic models, determine whether min or max probabilities needed
		if (modelType.nondeterministic()) {
			if (modelType == ModelType.MDP || modelType == ModelType.CTMDP || modelType == ModelType.SMG) {
				if (relOp.equals(">") || relOp.equals(">=") || relOp.equals("min=")) {
					// min
					min1 = true;
				} else if (relOp.equals("<") || relOp.equals("<=") || relOp.equals("max=")) {
					// max
					min1 = false;
				} else {
					throw new PrismException("Can't use \"P=?\" for nondeterministic models; use \"Pmin=?\" or \"Pmax=?\"");
				}
			} else if (modelType == ModelType.STPG) {
				if (relOp.equals("minmin=")) {
					min1 = true;
					min2 = true;
				} else if (relOp.equals("minmax=")) {
					min1 = true;
					min2 = false;
				} else if (relOp.equals("maxmin=")) {
					min1 = false;
					min2 = true;
				} else if (relOp.equals("maxmax=")) {
					min1 = false;
					min2 = false;
				} else {
					throw new PrismException("Use e.g. \"Pminmax=?\" for stochastic games");
				}
			} 
			else {
				throw new PrismException("Don't know how to model check " + expr.getTypeOfPOperator() + " properties for " + modelType +"s");
			}
		}

		// Compute probabilities
		switch (modelType) {
		case CTMC:
			probs = ((CTMCModelChecker) this).checkProbPathFormula(model, expr.getExpression());
			break;
		case CTMDP:
			probs = ((CTMDPModelChecker) this).checkProbPathFormula(model, expr.getExpression(), min1);
			break;
		case DTMC:
			probs = ((DTMCModelChecker) this).checkProbPathFormula(model, expr.getExpression());
			break;
		case MDP:
			probs = ((MDPModelChecker) this).checkProbPathFormula(model, expr.getExpression(), min1);
			break;
		case STPG:
			probs = ((STPGModelChecker) this).checkProbPathFormula(model, expr.getExpression(), min1, min2);
			break;
		case SMG:
			probs = ((SMGModelChecker) this).checkProbPathFormula(model, expr, min1, !min1);
			break;
		default:
			throw new PrismException("Cannot model check " + expr + " for a " + modelType);
		}

		// Print out probabilities
		if (getVerbosity() > 5) {
			mainLog.print("\nProbabilities (non-zero only) for all states:\n");
			mainLog.print(probs);
		}

		// For =? properties, just return values
		if (pb == null) {
			return probs;
		}
		// Otherwise, compare against bound to get set of satisfying states
		else {
			BitSet sol = probs.getBitSetFromInterval(relOp, p);
			probs.clear();
			return sol;
		}
	}

	/**
	 * Model check an R operator expression and return the values for all states.
	 */
	protected Object checkExpressionReward(Model model, ExpressionReward expr) throws PrismException
	{
		Object rs; // Reward struct index
		RewardStruct rewStruct = null; // Reward struct object
		Expression rb; // Reward bound (expression)
		double r = 0; // Reward bound (actual value)
		String relOp; // Relational operator
		boolean min1 = false;
		boolean min2 = false;
		ModelType modelType = model.getModelType();
		StateValues rews = null;
		MCRewards mcRewards = null;
		MDPRewards mdpRewards = null;
		STPGRewards stpgRewards = null;
		int i;

		// Get info from reward operator
		rs = expr.getRewardStructIndex();
		relOp = expr.getRelOp();
		rb = expr.getReward();
		if (rb != null) {
			r = rb.evaluateDouble(constantValues);
			if (r < 0)
				throw new PrismException("Invalid reward bound " + r + " in R[] formula");
		}

		// For nondeterministic models, determine whether min or max probabilities needed
		if (modelType.nondeterministic()) {
			if (modelType == ModelType.MDP || modelType == ModelType.CTMDP) {
				if (relOp.equals(">") || relOp.equals(">=") || relOp.equals("min=")) {
					// min
					min1 = true;
				} else if (relOp.equals("<") || relOp.equals("<=") || relOp.equals("max=")) {
					// max
					min1 = false;
				} else {
					throw new PrismException("Can't use \"P=?\" for nondeterministic models; use \"Pmin=?\" or \"Pmax=?\"");
				}
			} else if (modelType == ModelType.STPG) {
				if (relOp.equals("minmin=")) {
					min1 = true;
					min2 = true;
				} else if (relOp.equals("minmax=")) {
					min1 = true;
					min2 = false;
				} else if (relOp.equals("maxmin=")) {
					min1 = false;
					min2 = true;
				} else if (relOp.equals("maxmax=")) {
					min1 = false;
					min2 = false;
				} else {
					throw new PrismException("Use e.g. \"Pminmax=?\" for stochastic games");
				}
			} else {
				throw new PrismException("Don't know how to model check " + expr.getTypeOfROperator() + " properties for " + modelType +"s");
			}
		}

		// Get reward info
		if (modulesFile == null)
			throw new PrismException("No model file to obtain reward structures");
		if (modulesFile.getNumRewardStructs() == 0)
			throw new PrismException("Model has no rewards specified");
		if (rs == null) {
			rewStruct = modulesFile.getRewardStruct(0);
		} else if (rs instanceof Expression) {
			i = ((Expression) rs).evaluateInt(constantValues);
			rs = new Integer(i); // for better error reporting below
			rewStruct = modulesFile.getRewardStruct(i - 1);
		} else if (rs instanceof String) {
			rewStruct = modulesFile.getRewardStructByName((String) rs);
		}
		if (rewStruct == null)
			throw new PrismException("Invalid reward structure index \"" + rs + "\"");

		// Build rewards
		ConstructRewards constructRewards = new ConstructRewards(mainLog);
		switch (modelType) {
		case CTMC:
		case DTMC:
			mcRewards = constructRewards.buildMCRewardStructure((DTMC) model, rewStruct, constantValues);
			break;
		case MDP:
			mdpRewards = constructRewards.buildMDPRewardStructure((MDP) model, rewStruct, constantValues);
			break;
		case STPG:
			stpgRewards = constructRewards.buildSTPGRewardStructure((STPG) model, rewStruct, constantValues);
			break;
		default:
			throw new PrismException("Cannot build rewards for " + modelType + "s");
		}

		// Compute rewards
		mainLog.println("Building reward structure...");
		switch (modelType) {
		case CTMC:
			rews = ((CTMCModelChecker) this).checkRewardFormula(model, mcRewards, expr.getExpression());
			break;
		case DTMC:
			rews = ((DTMCModelChecker) this).checkRewardFormula(model, mcRewards, expr.getExpression());
			break;
		case MDP:
			rews = ((MDPModelChecker) this).checkRewardFormula(model, mdpRewards, expr.getExpression(), min1);
			break;
		case STPG:
			rews = ((STPGModelChecker) this).checkRewardFormula(model, stpgRewards, expr.getExpression(), min1, min2);
			break;
		default:
			throw new PrismException("Cannot model check " + expr + " for " + modelType + "s");
		}

		// Print out probabilities
		if (getVerbosity() > 5) {
			mainLog.print("\nProbabilities (non-zero only) for all states:\n");
			mainLog.print(rews);
		}

		// For =? properties, just return values
		if (rb == null) {
			return rews;
		}
		// Otherwise, compare against bound to get set of satisfying states
		else {
			BitSet sol = rews.getBitSetFromInterval(relOp, r);
			rews.clear();
			return sol;
		}
	}
}
