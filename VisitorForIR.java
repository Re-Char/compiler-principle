import org.llvm4j.llvm4j.*;
import org.llvm4j.llvm4j.Module;
import org.llvm4j.llvm4j.Type;
import org.llvm4j.llvm4j.VoidType; 
import org.llvm4j.optional.Option;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Stack;

public class VisitorForIR extends SysYParserBaseVisitor<Value> {
    private Context context;
    private Module module;
    private Function function;
    private IRBuilder builder;
    private IntegerType i32;
    private VoidType voidType;
    private ConstantInt zero;
    private Stack<Map<String, Value>> variables;
    private ArrayList<Type> paramTypes;
    private ArrayList<String> paramNames;
    private Stack<ArrayList<Value>> paramStack;
    private Stack<BasicBlock> whileNextStack;
    private Stack<BasicBlock> whileCondStack;

    public VisitorForIR() {
        this.context = new Context();
        this.module = context.newModule("module");
        this.builder = context.newIRBuilder();
        this.i32 = context.getInt32Type();
        this.voidType = context.getVoidType();
        this.zero = i32.getConstant(0, false);
        this.variables = new Stack<>();
        this.variables.push(new HashMap<>());
        this.paramTypes = new ArrayList<>();
        this.paramNames = new ArrayList<>();
        this.paramStack = new Stack<>();
        this.whileNextStack = new Stack<>();
        this.whileCondStack = new Stack<>();
    }

    private Value getValue(String name) {
        for (int i = this.variables.size() - 1; i >= 0; i--) {
            Map<String, Value> tmp = this.variables.get(i);
            if (tmp.containsKey(name)) {
                return tmp.get(name);
            }
        }
        return null;
    }

    // private Value getValueInCurScope(String name) {
    //     return this.variables.peek().get(name);
    // }

    @Override
    public Value visitDecl(SysYParser.DeclContext ctx) {
        if (ctx.CONST() != null) {
            for (SysYParser.ConstDefContext constDefCtx : ctx.constDef()) {
                visit(constDefCtx);
            }
        } else if (ctx.varDef() != null) {
            for (SysYParser.VarDefContext varDefCtx : ctx.varDef()) {
                visit(varDefCtx);
            }
        }
        return null;
    }

    @Override
    public Value visitConstDef(SysYParser.ConstDefContext ctx) {
        String varName = ctx.IDENT().getText();
        Value rValue = visit(ctx.constInitVal().constExp().exp());
        if (variables.size() > 1) {
            Value localVar = builder.buildAlloca(i32, Option.of(varName));
            builder.buildStore(localVar, rValue);
            variables.peek().put(varName, localVar);
        } else {
            var gVal = module.addGlobalVariable(varName, i32, Option.empty()).unwrap();
            if (!(rValue instanceof Constant)) {
                Constant evalRValue = evalCaculate(ctx.constInitVal().constExp().exp());
                gVal.setInitializer(evalRValue);
            } else gVal.setInitializer((Constant) rValue);
            variables.peek().put(varName, gVal);
        }
        return null;
    }

    @Override
    public Value visitVarDef(SysYParser.VarDefContext ctx) {
        String varName = ctx.IDENT().getText();
        Value rValue = null;
        if (ctx.ASSIGN() != null) rValue = visit(ctx.initVal().exp()); 
        if (variables.size() > 1) {
            Value localVar = builder.buildAlloca(i32, Option.of(varName));
            if (rValue != null) builder.buildStore(localVar, rValue);
            variables.peek().put(varName, localVar);
        } else {
            var gVal = module.addGlobalVariable(varName, i32, Option.empty()).unwrap();
            if (rValue != null) {
                Constant evalRValue = evalCaculate(ctx.initVal().exp());
                gVal.setInitializer(evalRValue);
            } else gVal.setInitializer(zero);
            variables.peek().put(varName, gVal);
        }
        return null;
    }

    @Override
    public Value visitFuncDef(SysYParser.FuncDefContext ctx) {
        String funcName = ctx.IDENT().getText();
        if (ctx.funcFParams() != null)
            visit(ctx.funcFParams());
        Type funcType = ctx.funcType().VOID() != null ? voidType : i32;
        var func = module.addFunction(funcName, context.getFunctionType(funcType, paramTypes.toArray(new Type[0]), false));
        function = func;
        var entryBlock = context.newBasicBlock(func.getName() + "Entry");
        func.addBasicBlock(entryBlock);
        builder.positionAfter(entryBlock);
        visit(ctx.block());
        return null;
    }

    @Override
    public Value visitFuncFParams(SysYParser.FuncFParamsContext ctx) {
        for (SysYParser.FuncFParamContext funcFParamCtx : ctx.funcFParam()) {
            String varName = funcFParamCtx.IDENT().getText();
            paramNames.add(varName);
            paramTypes.add(i32);
        }
        return null;
    }

    @Override
    public Value visitBlock(SysYParser.BlockContext ctx) {
        variables.push(new HashMap<>());
        for (int i = 0; i < paramNames.size(); i++) {
            String paramName = paramNames.get(i);
            Value paramValue = function.getParameter(i).unwrap();
            Value paramAlloca = builder.buildAlloca(i32, Option.of(paramName));
            builder.buildStore(paramAlloca, paramValue);
            variables.peek().put(paramName, paramAlloca);
        }
        paramNames.clear();
        paramTypes.clear();
        for (SysYParser.BlockItemContext blockItemContext : ctx.blockItem())
            visit(blockItemContext);
        if (variables.size() == 2 && (ctx.blockItem().size() == 0 || ctx.blockItem(ctx.blockItem().size() - 1).stmt() == null || ctx.blockItem(ctx.blockItem().size() - 1).stmt().RETURN() == null)) { 
            if (function.getType().getAsString().contains("void"))
                builder.buildReturn(Option.empty());
            else builder.buildReturn(Option.of(zero));
        }
        variables.pop();
        return null;
    }

    @Override
    public Value visitBlockItem(SysYParser.BlockItemContext ctx) {
        if (ctx.decl() != null) {
            visit(ctx.decl());
        } else if (ctx.stmt() != null) {
            visit(ctx.stmt());
        }
        return null;
    }

    @Override
    public Value visitStmt(SysYParser.StmtContext ctx) {
        if (ctx.RETURN() != null) {
            String funcType = function.getType().getAsString();
            if (funcType.contains("void")) {
                builder.buildReturn(Option.empty());
            } else {
                Value returnValue = visit(ctx.exp());
                builder.buildReturn(Option.of(returnValue));
            }
        } else if (ctx.ASSIGN() != null) {
            Value rValue = visit(ctx.exp());
            String varName = ctx.lVal().IDENT().getText();
            builder.buildStore(getValue(varName), rValue);
        } else if (ctx.exp() != null) {
            visit(ctx.exp());
        } else if (ctx.WHILE() != null) {
            var condBlock = context.newBasicBlock("whileCond");
            var bodyBlock = context.newBasicBlock("whileBody");
            var nextBlock = context.newBasicBlock("whileNext");
            whileCondStack.push(condBlock);
            whileNextStack.push(nextBlock);
            builder.buildBranch(condBlock);
            function.addBasicBlock(condBlock);
            builder.positionAfter(condBlock);
            Value res = builder.buildIntCompare(IntPredicate.NotEqual, visit(ctx.cond()), zero, Option.of("cond"));
            builder.buildConditionalBranch(res, bodyBlock, nextBlock);
            function.addBasicBlock(bodyBlock);
            builder.positionAfter(bodyBlock);
            visit(ctx.stmt(0));
            builder.buildBranch(condBlock);
            function.addBasicBlock(nextBlock);
            builder.positionAfter(nextBlock);
            if (whileCondStack.size() > 1) whileCondStack.pop();
            if (whileNextStack.size() > 1) whileNextStack.pop();
        } else if (ctx.IF() != null) {
            Value res = builder.buildIntCompare(IntPredicate.NotEqual, visit(ctx.cond()), zero, Option.of("cond"));
            var thenBlock = context.newBasicBlock("if_true_");
            var elseBlock = context.newBasicBlock("if_false_");
            var nextBlock = context.newBasicBlock("if_next_");
            builder.buildConditionalBranch(res, thenBlock, elseBlock);
            function.addBasicBlock(thenBlock);
            builder.positionAfter(thenBlock);
            visit(ctx.stmt(0));
            builder.buildBranch(nextBlock);
            function.addBasicBlock(elseBlock);
            builder.positionAfter(elseBlock);
            if (ctx.ELSE() != null) {
                visit(ctx.stmt(1));
            }
            builder.buildBranch(nextBlock);
            function.addBasicBlock(nextBlock);
            builder.positionAfter(nextBlock);
        } else if (ctx.BREAK() != null) {
            builder.buildBranch(whileNextStack.peek());
            // whileNextStack.pop();
            // whileCondStack.pop();
        } else if (ctx.CONTINUE() != null) {
            builder.buildBranch(whileCondStack.peek());
        } else if (ctx.block() != null) {
            visit(ctx.block());
        }
        return null;
    }

    @Override
    public Value visitCond(SysYParser.CondContext ctx) {
        if (ctx.exp() != null)
            return visit(ctx.exp());
        if (ctx.OR() != null || ctx.AND() != null) {
            Value resultAlloca = builder.buildAlloca(i32, Option.of("logictmp"));
            Value leftValue = visit(ctx.cond(0));
            Value leftCond = builder.buildIntCompare(IntPredicate.NotEqual, leftValue, zero, Option.of("leftcond"));
            BasicBlock evalRightBlock = context.newBasicBlock("eval_right");
            BasicBlock mergeBlock = context.newBasicBlock("merge_logic");
            if (ctx.OR() != null) {
                builder.buildStore(resultAlloca, i32.getConstant(1, false));
                builder.buildConditionalBranch(leftCond, mergeBlock, evalRightBlock);
                function.addBasicBlock(evalRightBlock);
                builder.positionAfter(evalRightBlock);
                Value rightValue = visit(ctx.cond(1));
                Value rightCond = builder.buildIntCompare(IntPredicate.NotEqual, rightValue, zero, Option.of("rightcond"));
                Value rightResult = builder.buildZeroExt(rightCond, i32, Option.of("rightresult"));
                builder.buildStore(resultAlloca, rightResult);
                builder.buildBranch(mergeBlock);
            } else if (ctx.AND() != null) {
                builder.buildStore(resultAlloca, zero);
                builder.buildConditionalBranch(leftCond, evalRightBlock, mergeBlock);
                function.addBasicBlock(evalRightBlock);
                builder.positionAfter(evalRightBlock);
                Value rightValue = visit(ctx.cond(1));
                Value rightCond = builder.buildIntCompare(IntPredicate.NotEqual, rightValue, zero, Option.of("rightcond"));
                Value rightResult = builder.buildZeroExt(rightCond, i32, Option.of("rightresult"));
                builder.buildStore(resultAlloca, rightResult);
                builder.buildBranch(mergeBlock);
            }
            function.addBasicBlock(mergeBlock);
            builder.positionAfter(mergeBlock);
            return builder.buildLoad(resultAlloca, Option.of("logicresult"));
        }
        Value left = visit(ctx.cond(0));
        Value right = visit(ctx.cond(1));
        if (ctx.EQ() != null || ctx.NEQ() != null) {
            if (ctx.EQ() != null) {
                Value res = builder.buildIntCompare(IntPredicate.Equal, left, right, Option.of("eqtmp"));
                return builder.buildZeroExt(res, i32, Option.of("extractcmp"));
            } else if (ctx.NEQ() != null) {
                Value res = builder.buildIntCompare(IntPredicate.NotEqual, left, right, Option.of("neqtmp"));
                return builder.buildZeroExt(res, i32, Option.of("extractcmp"));
            }
        } else if (ctx.LT() != null || ctx.GT() != null || ctx.LE() != null || ctx.GE() != null) {
            Value res = null;
            if (ctx.LT() != null) {
                res = builder.buildIntCompare(IntPredicate.SignedLessThan, left, right, Option.of("lttmp"));
            } else if (ctx.GT() != null) {
                res =  builder.buildIntCompare(IntPredicate.SignedGreaterThan, left, right, Option.of("gttmp"));
            } else if (ctx.LE() != null) {
                res = builder.buildIntCompare(IntPredicate.SignedLessEqual, left, right, Option.of("letmp"));
            } else if (ctx.GE() != null) {
                res = builder.buildIntCompare(IntPredicate.SignedGreaterEqual, left, right, Option.of("getmp"));
            }
            return builder.buildZeroExt(res, i32, Option.of("extractcmp"));
        }
        return null;
    }

    @Override
    public Value visitExp(SysYParser.ExpContext ctx) {
        if (ctx.L_PAREN() != null && ctx.exp().size() == 1) {
            return visit(ctx.exp(0));
        } else if (ctx.PLUS() != null || ctx.MINUS() != null) {
            Value left = visit(ctx.exp(0));
            Value right = visit(ctx.exp(1));
            if (ctx.PLUS() != null) {
                return builder.buildIntAdd(left, right, WrapSemantics.Unspecified, Option.of("addtmp"));
            } else if (ctx.MINUS() != null) {
                return builder.buildIntSub(left, right, WrapSemantics.Unspecified, Option.of("subtmp"));
            }
        } else if (ctx.MUL() != null || ctx.DIV() != null || ctx.MOD() != null) {
            Value left = visit(ctx.exp(0));
            Value right = visit(ctx.exp(1));
            if (ctx.MUL() != null) {
                return builder.buildIntMul(left, right, WrapSemantics.Unspecified, Option.of("multmp"));
            } else if (ctx.DIV() != null) {
                return builder.buildSignedDiv(left, right, false, Option.of("divtmp"));
            } else if (ctx.MOD() != null) {
                return builder.buildSignedRem(left, right, Option.of("modtmp"));
            }
        } else if (ctx.unaryOp() != null) {
            Value operand = visit(ctx.exp(0));
            if (ctx.unaryOp().getText().equals("-")) {
                return builder.buildIntSub(zero, operand, WrapSemantics.Unspecified, Option.of("negtmp"));
            } else if (ctx.unaryOp().getText().equals("!")) {
                Value cmpResult = builder.buildIntCompare(IntPredicate.Equal, operand, zero, Option.of("cmptmp"));
                return builder.buildZeroExt(cmpResult, i32, Option.of("unaryminus"));
            } 
            return operand;
        } else if (ctx.lVal() != null) {
            String varName = ctx.lVal().IDENT().getText();
            Value value = getValue(varName);
            if (value != null) {
                return builder.buildLoad(value, Option.of(varName));
            }
        } else if (ctx.number() != null) {
            String number = ctx.number().getText();
            if (number.startsWith("0x") || number.startsWith("0X")) 
                return i32.getConstant((int) Long.parseLong(number.substring(2), 16), false);
            else if (number.startsWith("0"))
                number = String.valueOf(Integer.parseInt(number, 8));
            return i32.getConstant((int)Long.parseLong(number), false);
        } else if (ctx.IDENT() != null) {
            paramStack.push(new ArrayList<>());
            String varName = ctx.IDENT().getText();
            // System.out.println("varName: " + varName);
            Function func = module.getFunction(varName).unwrap();
            if (ctx.funcRParams() != null) visit(ctx.funcRParams());
            ArrayList<Value> paramValues = paramStack.peek();
            paramStack.pop();
            if (func.getType().getAsString().contains("void")) {
                builder.buildCall(func, paramValues.toArray(new Value[0]), Option.empty());
                return null;
            } else return builder.buildCall(func, paramValues.toArray(new Value[0]), Option.of("call"));
        }
        return null;
    }
    
    @Override
    public Value visitFuncRParams(SysYParser.FuncRParamsContext ctx) {
        for (SysYParser.ParamContext paramContext : ctx.param()) {
            Value paramValue = visit(paramContext.exp());
            paramStack.peek().add(paramValue);
        }
        return null;
    }

    private ConstantInt evalCaculate(SysYParser.ExpContext etx) {
        if (etx.L_PAREN() != null && etx.exp().size() == 1) {
            return evalCaculate(etx.exp(0));
        } else if (etx.PLUS() != null || etx.MINUS() != null) {
            ConstantInt left = evalCaculate(etx.exp(0));
            ConstantInt right = evalCaculate(etx.exp(1));
            if (etx.PLUS() != null) {
                return i32.getConstant((int)left.getSignExtendedValue() + (int)right.getSignExtendedValue(), false);
            } else if (etx.MINUS() != null) {
                return i32.getConstant((int) left.getSignExtendedValue() - (int) right.getSignExtendedValue(), false);
            }
        } else if (etx.MUL() != null || etx.DIV() != null || etx.MOD() != null) {
            ConstantInt left = evalCaculate(etx.exp(0));
            ConstantInt right = evalCaculate(etx.exp(1));
            if (etx.MUL() != null) {
                return i32.getConstant((int) left.getSignExtendedValue() * (int) right.getSignExtendedValue(), false);
            } else if (etx.DIV() != null) {
                return i32.getConstant((int) left.getSignExtendedValue() / (int) right.getSignExtendedValue(), false);
            } else if (etx.MOD() != null) {
                return i32.getConstant((int) left.getSignExtendedValue() % (int) right.getSignExtendedValue(), false);
            }
        } else if (etx.unaryOp() != null) {
            ConstantInt operand = evalCaculate(etx.exp(0));
            if (etx.unaryOp().getText().equals("-")) {
                return i32.getConstant(0 - (int) operand.getSignExtendedValue(), false);
            } else if (etx.unaryOp().getText().equals("!")) {
                return i32.getConstant(operand.getSignExtendedValue() == 0 ? 1 : 0, false);
            }
            return operand;
        } else if (etx.number() != null) {
            String number = etx.number().getText();
            if (number.startsWith("0x") || number.startsWith("0X")) 
                number = String.valueOf(Integer.parseInt(number.substring(2), 16));
            else if (number.startsWith("0")) 
                number = String.valueOf(Integer.parseInt(number, 8));
            return i32.getConstant(Integer.parseInt(number), false);
        }
        return null;
    }
    
    public void generateIR(File outputFile) {
        module.dump(Option.of(outputFile));
    }

    public Module getModule() {
        return this.module;
    }
}