import org.bytedeco.llvm.LLVM.*;
import static org.bytedeco.llvm.global.LLVM.*;
import org.llvm4j.llvm4j.Module;

import java.util.*;

public class ConstantSpread {
    // 常量传播的数据流值表示
    private enum LatticeValue {
        UNDEF, // 未初始化
        NAC, // Not a Constant
        CONST // 常量值 (需要与具体值一起使用)
    }

    private static class ConstantValue {
        LatticeValue type;
        LLVMValueRef constValue; // 当type为CONST时有意义

        public ConstantValue(LatticeValue type) {
            this.type = type;
            this.constValue = null;
        }

        public ConstantValue(LLVMValueRef constValue) {
            this.type = LatticeValue.CONST;
            this.constValue = constValue;
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof ConstantValue))
                return false;
            ConstantValue other = (ConstantValue) obj;

            if (this.type != other.type)
                return false;
            if (this.type != LatticeValue.CONST)
                return true; // UNDEF和NAC只需比较类型

            // 对于常量，需要比较具体值
            if (this.constValue == null || other.constValue == null)
                return false;

            // 比较两个LLVMValueRef是否相等
            if (LLVMIsConstant(this.constValue) != 0 && LLVMIsConstant(other.constValue) != 0) {
                return LLVMConstIntGetSExtValue(this.constValue) == LLVMConstIntGetSExtValue(other.constValue);
            }

            return false;
        }
    }

    // 用于表示指令及其前驱后继关系
    private static class InstructionNode {
        LLVMValueRef instruction;
        List<InstructionNode> predecessors = new ArrayList<>();
        List<InstructionNode> successors = new ArrayList<>();

        // 用于常量传播分析
        Map<LLVMValueRef, ConstantValue> inValue = new HashMap<>(); // 存储变量到常量值的映射
        Map<LLVMValueRef, ConstantValue> outValue = new HashMap<>(); // 存储变量到常量值的映射

        public InstructionNode(LLVMValueRef instruction) {
            this.instruction = instruction;
        }
    }

    private LLVMModuleRef modRef;
    private int count = 0;
    private Map<LLVMValueRef, InstructionNode> instructionMap = new LinkedHashMap<>();
    // private Map<LLVMValueRef, ConstantValue> valueMap = new HashMap<>(); //
    // 存储变量到常量值的映射
    private Set<LLVMValueRef> globalVarsAssignedOnce = new HashSet<>(); // 只被赋值一次的全局变量

    public ConstantSpread(Module module) {
        this.modRef = module.getRef();
        identifyGlobalsAssignedOnce();
    }

    // 识别只被赋值一次的全局变量
    private void identifyGlobalsAssignedOnce() {
        Map<LLVMValueRef, Integer> globalAssignmentCount = new HashMap<>();
        for (LLVMValueRef globalVar = LLVMGetFirstGlobal(modRef); globalVar != null; globalVar = LLVMGetNextGlobal(
                globalVar)) {
            globalAssignmentCount.put(globalVar, 1);
        }
        for (LLVMValueRef func = LLVMGetFirstFunction(modRef); func != null; func = LLVMGetNextFunction(func)) {
            for (LLVMBasicBlockRef block = LLVMGetFirstBasicBlock(func); block != null; block = LLVMGetNextBasicBlock(
                    block)) {
                for (LLVMValueRef inst = LLVMGetFirstInstruction(block); inst != null; inst = LLVMGetNextInstruction(
                        inst)) {
                    if (LLVMGetInstructionOpcode(inst) == LLVMStore) {
                        LLVMValueRef pointer = LLVMGetOperand(inst, 1); // 存储目标
                        if (LLVMIsAGlobalVariable(pointer) != null) {
                            globalAssignmentCount.put(pointer, globalAssignmentCount.getOrDefault(pointer, 1) + 1);
                        }
                    }
                }
            }
        }

        // 添加只赋值一次的全局变量
        for (Map.Entry<LLVMValueRef, Integer> entry : globalAssignmentCount.entrySet()) {
            if (entry.getValue() == 1) {
                globalVarsAssignedOnce.add(entry.getKey());

            }
        }
    }

    // 构建控制流图
    public void buildCFG() {
        instructionMap.clear();
        for (LLVMValueRef func = LLVMGetFirstFunction(modRef); func != null; func = LLVMGetNextFunction(func)) {

            if (LLVMCountBasicBlocks(func) == 0)
                continue; // 跳过声明

            // 第一遍：创建所有指令节点
            Map<LLVMBasicBlockRef, List<InstructionNode>> blockInstructions = new HashMap<>();

            for (LLVMBasicBlockRef block = LLVMGetFirstBasicBlock(func); block != null; block = LLVMGetNextBasicBlock(
                    block)) {

                List<InstructionNode> instructions = new ArrayList<>();
                blockInstructions.put(block, instructions);

                for (LLVMValueRef inst = LLVMGetFirstInstruction(block); inst != null; inst = LLVMGetNextInstruction(
                        inst)) {

                    InstructionNode node = new InstructionNode(inst);
                    instructions.add(node);
                    instructionMap.put(inst, node);
                }
            }

            // 第二遍：建立指令间的前驱后继关系
            for (LLVMBasicBlockRef block = LLVMGetFirstBasicBlock(func); block != null; block = LLVMGetNextBasicBlock(
                    block)) {

                List<InstructionNode> instructions = blockInstructions.get(block);

                // 块内指令的线性前驱后继关系
                for (int i = 0; i < instructions.size() - 1; i++) {
                    InstructionNode current = instructions.get(i);
                    InstructionNode next = instructions.get(i + 1);
                    current.successors.add(next);
                    next.predecessors.add(current);
                }

                // 处理块间跳转
                LLVMValueRef terminator = LLVMGetBasicBlockTerminator(block);
                if (terminator != null) {
                    InstructionNode terminatorNode = instructionMap.get(terminator);

                    switch (LLVMGetInstructionOpcode(terminator)) {
                        case LLVMBr:
                            int numOperands = LLVMGetNumOperands(terminator);
                            if (numOperands == 1) { // 无条件跳转
                                LLVMBasicBlockRef targetBlock = LLVMValueAsBasicBlock(LLVMGetOperand(terminator, 0));
                                if (!blockInstructions.containsKey(targetBlock))
                                    continue;

                                InstructionNode firstInTarget = blockInstructions.get(targetBlock).get(0);
                                terminatorNode.successors.add(firstInTarget);
                                firstInTarget.predecessors.add(terminatorNode);
                            } else { // 条件跳转
                                LLVMBasicBlockRef trueBlock = LLVMValueAsBasicBlock(LLVMGetOperand(terminator, 2));
                                LLVMBasicBlockRef falseBlock = LLVMValueAsBasicBlock(LLVMGetOperand(terminator, 1));

                                if (blockInstructions.containsKey(trueBlock)) {
                                    InstructionNode firstInTrue = blockInstructions.get(trueBlock).get(0);
                                    terminatorNode.successors.add(firstInTrue);
                                    firstInTrue.predecessors.add(terminatorNode);
                                }

                                if (blockInstructions.containsKey(falseBlock)) {
                                    InstructionNode firstInFalse = blockInstructions.get(falseBlock).get(0);
                                    terminatorNode.successors.add(firstInFalse);
                                    firstInFalse.predecessors.add(terminatorNode);
                                }
                            }
                            break;
                        case LLVMRet:
                            // 返回指令没有后继
                            break;
                        default:
                            // 处理其他终结指令
                            break;
                    }
                }
            }
        }
    }

    // 执行常量传播算法
    public void performConstantPropagation() {
        // 初始化worklist为所有指令
        Queue<InstructionNode> worklist = new LinkedList<>(instructionMap.values());

        while (!worklist.isEmpty()) {
            InstructionNode node = worklist.poll();
            Map<LLVMValueRef, ConstantValue> oldOutValue = node.outValue;
            // 计算in值 - 所有前驱的out值的meet
            Map<LLVMValueRef, ConstantValue> inValue = new HashMap<>(); // 存储变量到常量值的映射
            for (InstructionNode pred : node.predecessors) {
                for (Map.Entry<LLVMValueRef, ConstantValue> entry : pred.outValue.entrySet()) {
                    LLVMValueRef var = entry.getKey();
                    ConstantValue value = entry.getValue();
                    // 计算meet
                    if (inValue.containsKey(var)) {
                        inValue.put(var, meet(inValue.get(var), value));
                    } else {
                        inValue.put(var, meet(new ConstantValue(LatticeValue.UNDEF), value));
                    }
                }
            }
            node.inValue = inValue;
            node.outValue = transfer(node);

            // 如果out值改变了，将所有后继加入worklist
            if (!mapsEqual(oldOutValue, node.outValue)) {
                worklist.addAll(node.successors);
            }
        }
    }

    // 比较两个映射是否相等
    private boolean mapsEqual(Map<LLVMValueRef, ConstantValue> map1, Map<LLVMValueRef, ConstantValue> map2) {
        if (map1 == map2)
            return true;
        if (map1 == null || map2 == null)
            return false;
        if (map1.size() != map2.size())
            return false;

        for (Map.Entry<LLVMValueRef, ConstantValue> entry : map1.entrySet()) {
            LLVMValueRef key = entry.getKey();
            ConstantValue value1 = entry.getValue();
            ConstantValue value2 = map2.get(key);

            if (value2 == null || !value1.equals(value2)) {
                return false;
            }
        }

        return true;
    }

    // 实现meet函数
    private ConstantValue meet(ConstantValue v1, ConstantValue v2) {
        if (v1.type == LatticeValue.UNDEF)
            return v2;
        if (v2.type == LatticeValue.UNDEF)
            return v1;
        if (v1.equals(v2))
            return v1;
        return new ConstantValue(LatticeValue.NAC);
    }

    // 实现transfer函数
    private Map<LLVMValueRef, ConstantValue> transfer(InstructionNode node) {
        LLVMValueRef inst = node.instruction;
        int opcode = LLVMGetInstructionOpcode(inst);

        // 拷贝输入值
        Map<LLVMValueRef, ConstantValue> inValues = new HashMap<>(node.inValue);

        switch (opcode) {
            case LLVMAlloca:
                // 为变量分配内存，初值是UNDEF
                inValues.put(inst, new ConstantValue(LatticeValue.UNDEF));
                break;

            case LLVMLoad:
                LLVMValueRef pointer = LLVMGetOperand(inst, 0);
                // String pointerName = LLVMGetValueName(pointer).getString();

                if (LLVMIsAGlobalVariable(pointer) != null) {
                    LLVMValueRef initializer = LLVMGetInitializer(pointer);
                    if (initializer != null && LLVMIsConstant(initializer) != 0) {
                        ConstantValue loadValue = new ConstantValue(initializer);
                        if (inValues.containsKey(pointer)) {
                            loadValue = inValues.get(pointer);
                        }
                        inValues.put(inst, loadValue);
                    } else if (inValues.containsKey(pointer)) {
                        inValues.put(inst, inValues.get(pointer));
                    } else {
                        inValues.put(inst, new ConstantValue(LatticeValue.NAC));
                    }
                } else {
                    if (inValues.containsKey(pointer)) {
                        inValues.put(inst, inValues.get(pointer));
                    } else {
                        inValues.put(inst, new ConstantValue(LatticeValue.NAC));
                    }
                }
                break;

            case LLVMStore:
                LLVMValueRef value = LLVMGetOperand(inst, 0);
                LLVMValueRef storePointer = LLVMGetOperand(inst, 1);

                // 如果存储的是常量
                if (LLVMIsConstant(value) != 0) {
                    inValues.put(storePointer, new ConstantValue(value));
                }
                // 如果存储的是变量，检查该变量是否为常量
                else if (inValues.containsKey(value)) {
                    inValues.put(storePointer, inValues.get(value));
                }
                // 否则，存储的值是NAC
                else {
                    inValues.put(storePointer, new ConstantValue(LatticeValue.NAC));
                }
                break;

            case LLVMAdd:
            case LLVMSub:
            case LLVMMul:
            case LLVMSDiv:
            case LLVMSRem:
                LLVMValueRef op1 = LLVMGetOperand(inst, 0);
                LLVMValueRef op2 = LLVMGetOperand(inst, 1);

                ConstantValue v1 = getConstantValue(op1, inValues);
                ConstantValue v2 = getConstantValue(op2, inValues);

                // 如果两个操作数都是常量，计算结果
                if (v1.type == LatticeValue.CONST && v2.type == LatticeValue.CONST) {
                    int val1 = (int) LLVMConstIntGetSExtValue(v1.constValue);
                    int val2 = (int) LLVMConstIntGetSExtValue(v2.constValue);
                    int result = 0;

                    switch (opcode) {
                        case LLVMAdd:
                            result = val1 + val2;
                            break;
                        case LLVMSub:
                            result = val1 - val2;
                            break;
                        case LLVMMul:
                            result = val1 * val2;
                            break;
                        case LLVMSDiv:
                            if (val2 != 0)
                                result = val1 / val2;
                            else {
                                inValues.put(inst, new ConstantValue(LatticeValue.NAC)); // 除零错误
                                return inValues;
                            }
                            break;
                        case LLVMSRem:
                            if (val2 != 0)
                                result = val1 % val2;
                            else {
                                inValues.put(inst, new ConstantValue(LatticeValue.NAC)); // 除零错误
                                return inValues;
                            }
                            break;
                    }

                    LLVMContextRef context = LLVMGetModuleContext(modRef);
                    LLVMTypeRef i32Type = LLVMInt32TypeInContext(context);
                    LLVMValueRef constResult = LLVMConstInt(i32Type, result, 0);
                    inValues.put(inst, new ConstantValue(constResult));
                }
                // 如果有一个操作数是NAC，结果是NAC
                else if (v1.type == LatticeValue.NAC || v2.type == LatticeValue.NAC) {
                    inValues.put(inst, new ConstantValue(LatticeValue.NAC));
                }
                // 其他情况，结果是UNDEF
                else {
                    inValues.put(inst, new ConstantValue(LatticeValue.UNDEF));
                }
                break;

            case LLVMICmp:
                LLVMValueRef icmpOp1 = LLVMGetOperand(inst, 0);
                LLVMValueRef icmpOp2 = LLVMGetOperand(inst, 1);
                int predicate = LLVMGetICmpPredicate(inst);

                ConstantValue icmpV1 = getConstantValue(icmpOp1, inValues);
                ConstantValue icmpV2 = getConstantValue(icmpOp2, inValues);

                // 如果两个操作数都是常量，计算结果
                if (icmpV1.type == LatticeValue.CONST && icmpV2.type == LatticeValue.CONST) {
                    int icmpVal1 = (int) LLVMConstIntGetSExtValue(icmpV1.constValue);
                    int icmpVal2 = (int) LLVMConstIntGetSExtValue(icmpV2.constValue);
                    boolean result = false;

                    switch (predicate) {
                        case LLVMIntEQ:
                            result = icmpVal1 == icmpVal2;
                            break;
                        case LLVMIntNE:
                            result = icmpVal1 != icmpVal2;
                            break;
                        case LLVMIntSGT:
                            result = icmpVal1 > icmpVal2;
                            break;
                        case LLVMIntSGE:
                            result = icmpVal1 >= icmpVal2;
                            break;
                        case LLVMIntSLT:
                            result = icmpVal1 < icmpVal2;
                            break;
                        case LLVMIntSLE:
                            result = icmpVal1 <= icmpVal2;
                            break;
                    }

                    LLVMContextRef context = LLVMGetModuleContext(modRef);
                    LLVMTypeRef i1Type = LLVMInt1TypeInContext(context);
                    LLVMValueRef constResult = LLVMConstInt(i1Type, result ? 1 : 0, 0);
                    inValues.put(inst, new ConstantValue(constResult));
                }
                // 如果有一个操作数是NAC，结果是NAC
                else if (icmpV1.type == LatticeValue.NAC || icmpV2.type == LatticeValue.NAC) {
                    inValues.put(inst, new ConstantValue(LatticeValue.NAC));
                }
                // 其他情况，结果是UNDEF
                else {
                    inValues.put(inst, new ConstantValue(LatticeValue.UNDEF));
                }
                break;

            case LLVMZExt:
            case LLVMSExt:
                LLVMValueRef extOp = LLVMGetOperand(inst, 0);
                ConstantValue extVal = getConstantValue(extOp, inValues);

                if (extVal.type == LatticeValue.CONST) {
                    int extValue = (int) LLVMConstIntGetSExtValue(extVal.constValue);
                    LLVMTypeRef destType = LLVMTypeOf(inst);
                    LLVMValueRef constResult = LLVMConstInt(destType, extValue, 0);
                    inValues.put(inst, new ConstantValue(constResult));
                } else {
                    inValues.put(inst, extVal); // 传递UNDEF或NAC
                }
                break;

            // 其他指令类型...
            default:
                // 对于未处理的指令类型，假设结果是NAC
                inValues.put(inst, new ConstantValue(LatticeValue.NAC));
                break;
        }

        // 返回当前指令的结果值
        return inValues;
    }

    private ConstantValue getConstantValue(LLVMValueRef operand, Map<LLVMValueRef, ConstantValue> values) {
        if (LLVMIsConstant(operand) != 0) {
            return new ConstantValue(operand);
        }

        if (values.containsKey(operand)) {
            return values.get(operand);
        }

        if (LLVMIsAInstruction(operand) != null) {
            return new ConstantValue(LatticeValue.NAC);
        }

        return new ConstantValue(LatticeValue.UNDEF);
    }

    public void replaceAndRemoveConstants() {
        List<LLVMValueRef> instructionsToRemove = new ArrayList<>();

        for (Map.Entry<LLVMValueRef, InstructionNode> entry : instructionMap.entrySet()) {
            LLVMValueRef inst = entry.getKey();
            InstructionNode node = entry.getValue();
            if (node.outValue.containsKey(inst) &&
                    node.outValue.get(inst).type == LatticeValue.CONST) {
                LLVMReplaceAllUsesWith(inst, node.outValue.get(inst).constValue);
                instructionsToRemove.add(inst);
                count++;
            }
        }

        for (LLVMValueRef inst : instructionsToRemove) {
            LLVMInstructionEraseFromParent(inst);
        }
    }

    // 执行优化
    public int optimize() {
        buildCFG();
        performConstantPropagation();
        replaceAndRemoveConstants();
        return count;
    }
}