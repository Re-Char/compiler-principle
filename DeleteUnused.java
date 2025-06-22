import org.bytedeco.llvm.LLVM.*;
import static org.bytedeco.llvm.global.LLVM.*;
import org.llvm4j.llvm4j.Module;

import java.util.*;

public class DeleteUnused {
    private LLVMModuleRef modRef;
    private int deletedCount = 0;
    private Set<LLVMValueRef> allocaInstructions = new HashSet<>();
    private Set<LLVMValueRef> usedVariables = new HashSet<>();
    private Map<LLVMValueRef, List<LLVMValueRef>> variableRelatedInsts = new HashMap<>();

    public DeleteUnused(Module module) {
        this.modRef = module.getRef();
    }

    private void analyze() {
        collectVariablesAndDependencies();
        markUsedVariables();
    }

    private void collectVariablesAndDependencies() {
        for (LLVMValueRef func = LLVMGetFirstFunction(modRef); func != null; func = LLVMGetNextFunction(func)) {
            if (LLVMCountBasicBlocks(func) == 0)
                continue;

            for (LLVMBasicBlockRef block = LLVMGetFirstBasicBlock(func); block != null; block = LLVMGetNextBasicBlock(block)) {
                for (LLVMValueRef inst = LLVMGetFirstInstruction(block); inst != null; inst = LLVMGetNextInstruction(
                        inst)) {
                    int opcode = LLVMGetInstructionOpcode(inst);

                    if (opcode == LLVMAlloca) {
                        allocaInstructions.add(inst);
                        variableRelatedInsts.put(inst, new ArrayList<>());
                    } else if (opcode == LLVMStore) {
                        LLVMValueRef ptr = LLVMGetOperand(inst, 1); // 存储目标
                        if (allocaInstructions.contains(ptr)) {
                            variableRelatedInsts.get(ptr).add(inst);
                        }
                    }
                }
            }
        }
    }

    private void markUsedVariables() {
        for (LLVMValueRef func = LLVMGetFirstFunction(modRef); func != null; func = LLVMGetNextFunction(func)) {
            if (LLVMCountBasicBlocks(func) == 0)
                continue;

            for (LLVMBasicBlockRef block = LLVMGetFirstBasicBlock(func); block != null; block = LLVMGetNextBasicBlock(
                    block)) {
                for (LLVMValueRef inst = LLVMGetFirstInstruction(block); inst != null; inst = LLVMGetNextInstruction(
                        inst)) {
                    int opcode = LLVMGetInstructionOpcode(inst);
                    switch (opcode) {
                        case LLVMLoad:
                            LLVMValueRef ptr = LLVMGetOperand(inst, 0);
                            if (allocaInstructions.contains(ptr)) {
                                usedVariables.add(ptr);
                            }
                            break;

                        case LLVMRet:
                            if (LLVMGetNumOperands(inst) > 0) {
                                LLVMValueRef returnValue = LLVMGetOperand(inst, 0);
                                if (allocaInstructions.contains(returnValue)) {
                                    usedVariables.add(returnValue);
                                }
                            }
                            break;

                        case LLVMBr:
                            if (LLVMGetNumOperands(inst) > 1) { 
                                LLVMValueRef condition = LLVMGetOperand(inst, 0);
                                if (allocaInstructions.contains(condition)) {
                                    usedVariables.add(condition);
                                }
                            }
                            break;

                        case LLVMICmp:
                            checkOperandUsage(LLVMGetOperand(inst, 0));
                            checkOperandUsage(LLVMGetOperand(inst, 1));
                            break;

                        case LLVMAdd:
                        case LLVMSub:
                        case LLVMMul:
                        case LLVMSDiv:
                        case LLVMSRem:
                            checkOperandUsage(LLVMGetOperand(inst, 0));
                            checkOperandUsage(LLVMGetOperand(inst, 1));
                            break;

                        case LLVMAnd:
                        case LLVMOr:
                        case LLVMXor:
                        case LLVMShl:
                        case LLVMLShr:
                        case LLVMAShr:
                            checkOperandUsage(LLVMGetOperand(inst, 0));
                            checkOperandUsage(LLVMGetOperand(inst, 1));
                            break;

                        case LLVMZExt:
                        case LLVMSExt:
                            checkOperandUsage(LLVMGetOperand(inst, 0));
                            break;
                    }
                }
            }
        }
    }

    private void checkOperandUsage(LLVMValueRef operand) {
        if (allocaInstructions.contains(operand)) {
            usedVariables.add(operand);
        }

        if (LLVMIsALoadInst(operand) != null) {
            LLVMValueRef loadPtr = LLVMGetOperand(operand, 0);
            if (allocaInstructions.contains(loadPtr)) {
                usedVariables.add(loadPtr);
            }
        }
    }

    private void simplifyConditions() {
        int simplifiedCount = 0;
        for (LLVMValueRef func = LLVMGetFirstFunction(modRef); func != null; func = LLVMGetNextFunction(func)) {
            if (LLVMCountBasicBlocks(func) == 0)
                continue;

            for (LLVMBasicBlockRef block = LLVMGetFirstBasicBlock(func); block != null; block = LLVMGetNextBasicBlock(
                    block)) {
                List<LLVMValueRef> toRemove = new ArrayList<>();
                for (LLVMValueRef inst = LLVMGetFirstInstruction(block); inst != null; inst = LLVMGetNextInstruction(
                        inst)) {
                    if (LLVMGetInstructionOpcode(inst) == LLVMBr && LLVMGetNumOperands(inst) > 1) {
                        LLVMValueRef condition = LLVMGetOperand(inst, 0); // 当前条件（%cond）

                        if (LLVMIsAICmpInst(condition) != null) {

                            if (LLVMGetICmpPredicate(condition) == LLVMIntNE) {
                                LLVMValueRef extractcmp = LLVMGetOperand(condition, 0); // %extractcmp
                                LLVMValueRef zero = LLVMGetOperand(condition, 1); // 应该是0

                                if (LLVMIsAConstantInt(zero) != null &&
                                        LLVMConstIntGetZExtValue(zero) == 0 &&
                                        LLVMGetInstructionOpcode(extractcmp) == LLVMZExt) {

                                    LLVMValueRef origCmp = LLVMGetOperand(extractcmp, 0);

                                    if (LLVMIsAICmpInst(origCmp) != null) {
                                        LLVMSetOperand(inst, 0, origCmp);
                                        toRemove.add(condition); // %cond
                                        toRemove.add(extractcmp); // %extractcmp
                                        // System.out.println("简化条件: 用 " +
                                        // LLVMPrintValueToString(origCmp).getString() +
                                        // " 替换了 " +
                                        // LLVMPrintValueToString(condition).getString());

                                        simplifiedCount += 2;
                                    }
                                }
                            }
                        }
                    }
                }

                for (int i = toRemove.size() - 1; i >= 0; i--) {
                    try {
                        LLVMInstructionEraseFromParent(toRemove.get(i));
                    } catch (Exception e) {
                        System.out.println("删除指令失败: " + e.getMessage());
                    }
                }
            }
        }

        if (simplifiedCount > 0) {
            // System.out.println("简化了 " + simplifiedCount + " 条条件指令");
            deletedCount += simplifiedCount;
        }
    }

    private void deleteUnused() {
        Set<LLVMValueRef> unusedVariables = new HashSet<>(allocaInstructions);
        unusedVariables.removeAll(usedVariables);
        deletedCount += unusedVariables.size();

        // System.out.println("找到 " + unusedVariables.size() + " 个未使用变量");

        for (LLVMValueRef var : unusedVariables) {
            List<LLVMValueRef> relatedInsts = variableRelatedInsts.get(var);
            if (relatedInsts != null) {
                for (LLVMValueRef relatedInst : relatedInsts) {
                    // System.out.println("删除关联指令: " + LLVMPrintValueToString(relatedInst).getString());
                    LLVMInstructionEraseFromParent(relatedInst);
                }
            }
        }

        for (LLVMValueRef var : unusedVariables) {
            // System.out.println("删除变量: " + LLVMPrintValueToString(var).getString());
            LLVMInstructionEraseFromParent(var);
        }
    }

    public int optimize() {
        analyze();
        simplifyConditions();
        deleteUnused();
        return deletedCount;
    }
}