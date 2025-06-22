import java.util.ArrayList;
import java.util.Stack;

public class VisitorForGrammar extends SysYParserBaseVisitor<Void> {
    private Scope curScope = new Scope();
    private ArrayList<String> errors = new ArrayList<>();
    private ArrayList<Type> paramsTyList = new ArrayList<>();
    private ArrayList<String> paramsNameList = new ArrayList<>();
    private Stack<ArrayList<Type>> paramsStack = new Stack<>();
    private String retFuncTyString = new String();
    private boolean flag1 = true;
    private boolean forOp = false;

    @Override
    public Void visitFuncDef(SysYParser.FuncDefContext ctx) {
        String funcName = ctx.IDENT().getText();
        if (curScope.contains(funcName)) {
            errors.add("Error type 4 at Line " + ctx.IDENT().getSymbol().getLine() + ": "
                    + "Redefined function: " + ctx.IDENT().getText());
            return null;
        }
        Type retType = VoidType.getVoidType();
        String typeStr = ctx.getChild(0).getText();
        if (typeStr.equals("int"))
            retType = IntType.getI32();
        retFuncTyString = retType.getTypeName();
        if (ctx.funcFParams() != null) 
            visit(ctx.funcFParams());
        FunctionType functionType = new FunctionType(retType, paramsTyList);
        curScope.put(funcName, functionType);
        visit(ctx.block());
        paramsTyList.clear();
        paramsNameList.clear();
        retFuncTyString = "";
        return null;
    }
    
    @Override
    public Void visitFuncFParams(SysYParser.FuncFParamsContext ctx) {
        for (SysYParser.FuncFParamContext param : ctx.funcFParam()) {
            String paramName = param.IDENT().getText();
            if (paramsNameList.contains(paramName)) {
                errors.add("Error type 3 at Line " + param.IDENT().getSymbol().getLine() + ": "
                        + "Redefined variable: " + paramName);
                continue;
            }
            if (param.L_BRACKT().size() > 0) {
                ArrayType arrType = new ArrayType(IntType.getI32(), 1);
                paramsTyList.add(arrType);
            } else paramsTyList.add(IntType.getI32());
            paramsNameList.add(paramName);
        }
        return null;
    }

    @Override
    public Void visitBlock(SysYParser.BlockContext ctx) {
        curScope.enterNewScope();
        if (paramsNameList.size() != 0) {
            for (int i = 0; i < paramsNameList.size(); i++)
                curScope.put(paramsNameList.get(i), paramsTyList.get(i));
        }
        paramsNameList.clear();
        ctx.blockItem().forEach(this::visit);
        curScope.exitScope();
        return null;
    }
    
    @Override
    public Void visitVarDef(SysYParser.VarDefContext ctx) {
        String varName = ctx.IDENT().getText();
        // System.out.println(varName);
        if (curScope.containsInCurScope(varName)) {
            errors.add("Error type 3 at Line " + ctx.IDENT().getSymbol().getLine() + ": "
                    + "Redefined variable: " + varName);
            return null;
        }
        if (ctx.constExp().isEmpty()) {
            if (ctx.ASSIGN() != null) {
                if (ctx.initVal().exp() != null)
                    handleAssignForInt(ctx.initVal().exp());
                else {
                    errors.add("Error type 5 at Line " + ctx.initVal().L_BRACE().getSymbol().getLine() + ": "
                            + "Type mismatched for assignment.");
                }
            }
            curScope.put(varName, IntType.getI32());
        } else {
            if (ctx.ASSIGN() != null) {
                if (ctx.initVal().exp() != null)
                    handleAssignForArr(ctx.initVal().exp(), ctx.constExp().size());
            }
            ArrayType arrType = new ArrayType(IntType.getI32(), ctx.constExp().size());
            curScope.put(varName, arrType);
        }
        return null;
    }

    @Override
    public Void visitConstDef(SysYParser.ConstDefContext ctx) {
        String varName = ctx.IDENT().getText();
        if (curScope.containsInCurScope(varName)) {
            errors.add("Error type 3 at Line " + ctx.IDENT().getSymbol().getLine() + ": "
                    + "Redefined variable: " + varName);
            return null;
        }
        if (ctx.constExp().isEmpty()) {
            if (ctx.constInitVal().constExp() != null)
                handleAssignForInt(ctx.constInitVal().constExp().exp());
            else {
                errors.add("Error type 5 at Line " + ctx.constInitVal().L_BRACE().getSymbol().getLine() + ": "
                        + "Type mismatched for assignment.");
            }
            curScope.put(varName, IntType.getI32());
        } else {
            if (ctx.constInitVal().constExp() != null)
                handleAssignForArr(ctx.constInitVal().constExp().exp(), ctx.constExp().size());
            ArrayType arrType = new ArrayType(IntType.getI32(), ctx.constExp().size());
            curScope.put(varName, arrType);
        }
        return null;

    }

    private Void handleAssignForInt(SysYParser.ExpContext ctx) {
        if (ctx.lVal() != null) {
            String lValName = ctx.lVal().IDENT().getText();
            if (!curScope.contains(lValName)) {
                errors.add("Error type 1 at Line " + ctx.lVal().IDENT().getSymbol().getLine() + ": "
                        + "Undefined variable: " + lValName);
                return null;
            }
            String lValTyString = curScope.getType(lValName).getTypeName();
            if (!(lValTyString.equals("INT") || (lValTyString.equals("ARRAY") && ((ArrayType) curScope.getType(lValName)).getCount() - ctx.lVal().exp().size() == 0))) {
                errors.add("Error type 5 at Line " + ctx.lVal().IDENT().getSymbol().getLine() + ": "
                        + "Type mismatched for assignment.");
                return null;
            } else {
                visit(ctx.lVal());
            }
        } else if (ctx.IDENT() != null && ctx.L_PAREN() != null) {
            String funcName = ctx.IDENT().getText();
            if (!curScope.contains(funcName)) {
                errors.add("Error type 2 at Line " + ctx.IDENT().getSymbol().getLine() + ": "
                        + "Undefined function: " + funcName);
                return null;
            }
            // System.out.println(funcName);
            String funcTyString = curScope.getType(funcName).getTypeName();
            if (!funcTyString.equals("FUNCTION")) {
                errors.add("Error type 10 at Line " + ctx.IDENT().getSymbol().getLine() + ": "
                        + "Not a function: " + funcName);
            } else {
                if (((FunctionType) curScope.getType(funcName)).getRetType() != IntType.getI32()) {
                    errors.add("Error type 5 at Line " + ctx.IDENT().getSymbol().getLine() + ": "
                            + "Type mismatched for assignment.");
                } else
                    visit(ctx);
            }
            return null;
        } else {
            visit(ctx);
        }
        return null;
    }

    private Void handleAssignForArr(SysYParser.ExpContext ctx, int lValDim) {
        if (ctx.lVal() != null) {
            String _lValName = ctx.lVal().IDENT().getText();
            String lValTyString = curScope.getType(_lValName).getTypeName();
            if (lValTyString.equals("FUNCTION")) {
                errors.add("Error type 5 at Line " + ctx.lVal().IDENT().getSymbol().getLine() + ": "
                        + "Type mismatched for assignment.");
            } else {
                int rValDim = 0;
                if (lValTyString.equals("ARRAY")) 
                    rValDim = ((ArrayType) curScope.getType(_lValName)).getCount() - ctx.lVal().exp().size();
                else rValDim -= ctx.lVal().exp().size();
                if (rValDim < 0) visit(ctx);
                else if (lValDim != rValDim) {
                    errors.add("Error type 5 at Line " + ctx.lVal().IDENT().getSymbol().getLine() + ": "
                        + "Type mismatched for assignment.");
                }
            }
        } else if (ctx.number() != null) {
            if (lValDim != 0) {
                errors.add("Error type 5 at Line " + ctx.number().getStart().getLine() + ": "
                    + "Type mismatched for assignment.");
            }
            visit(ctx);
        } else {
            visit(ctx);
        }
        return null;
    }

    @Override
    public Void visitStmt(SysYParser.StmtContext ctx) {
        if (ctx.ASSIGN() != null) {
            String varName = ctx.lVal().IDENT().getText();
            if (!curScope.contains(varName)) {
                errors.add("Error type 1 at Line " + ctx.lVal().IDENT().getSymbol().getLine() + ": "
                        + "Undefined variable: " + varName);
                if (ctx.exp().lVal() != null) {
                    String lValName = ctx.exp().lVal().IDENT().getText();
                    if (!curScope.contains(lValName)) {
                        errors.add("Error type 1 at Line " + ctx.exp().lVal().IDENT().getSymbol().getLine() + ": "
                                + "Undefined variable: " + lValName);
                    }
                }
            } else {
                Type varType = curScope.getType(varName);
                if (varType instanceof FunctionType) {
                    errors.add("Error type 11 at Line " + ctx.lVal().IDENT().getSymbol().getLine() + ": "
                            + "The left-hand side of an assignment must be a variable.");
                } else if (varType instanceof IntType) {
                    if (ctx.lVal().exp().size() != 0) {
                        errors.add("Error type 9 at Line " + ctx.lVal().IDENT().getSymbol().getLine() + ": "
                                + "Not an array: " + varName);
                    }
                    handleAssignForInt(ctx.exp());
                } else {
                    String lValName = ctx.lVal().IDENT().getText();
                    int lValdim = ((ArrayType) curScope.getType(lValName)).getCount() - ctx.lVal().exp().size();
                    if (lValdim < 0) {
                        errors.add("Error type 9 at Line " + ctx.lVal().IDENT().getSymbol().getLine() + ": "
                                + "Not an array: " + varName);
                    }
                    handleAssignForArr(ctx.exp(), lValdim);
                }
                return null;
            }
        } else if (ctx.RETURN() != null) {
            if (ctx.exp() != null) {
                if (retFuncTyString.equals("VOID")) {
                    errors.add("Error type 7 at Line " + ctx.RETURN().getSymbol().getLine() + ": "
                            + "type.Type mismatched for return."); 
                } else {
                    if (ctx.exp().lVal() != null) {
                        String lValName = ctx.exp().lVal().IDENT().getText();
                        if (!curScope.contains(lValName)) {
                            errors.add("Error type 1 at Line " + ctx.exp().lVal().IDENT().getSymbol().getLine() + ": "
                                    + "Undefined variable: " + lValName);
                        } else {
                            String lValTyString = curScope.getType(lValName).getTypeName();
                            if (!(lValTyString.equals("INT") || (lValTyString.equals("ARRAY") && ((ArrayType) curScope.getType(lValName)).getCount() - ctx.exp().lVal().exp().size() == 0))) {
                                errors.add("Error type 7 at Line " + ctx.RETURN().getSymbol().getLine() + ": "
                                        + "type.Type mismatched for return.");
                            } else {
                                visit(ctx.exp());
                            }
                        }
                    } else if (ctx.exp().L_PAREN() != null && ctx.exp().IDENT() != null) {
                        String funcName = ctx.exp().IDENT().getText();
                        if (!curScope.contains(funcName)) {
                            errors.add("Error type 2 at Line " + ctx.exp().IDENT().getSymbol().getLine() + ": "
                                    + "Undefined function: " + funcName);
                        } else {
                            String funcTyString = curScope.getType(funcName).getTypeName();
                            if (!funcTyString.equals("FUNCTION")) {
                                errors.add("Error type 10 at Line " + ctx.exp().IDENT().getSymbol().getLine() + ": "
                                        + "Not a function: " + funcName);
                            } else {
                                if (!(((FunctionType) curScope.getType(funcName)).getRetType() instanceof IntType)) {
                                    errors.add("Error type 7 at Line " + ctx.RETURN().getSymbol().getLine() + ": "
                                            + "type.Type mismatched for return.");
                                } else
                                    visit(ctx.exp());
                            }
                        }
                    } else {
                        visit(ctx.exp());
                    }
                }
            } else {
                if (!retFuncTyString.equals("VOID")) {
                    errors.add("Error type 7 at Line " + ctx.RETURN().getSymbol().getLine() + ": "
                            + "type.Type mismatched for return.");
                }
            }
        } else if (ctx.IF() != null || ctx.WHILE() != null) {
            visit(ctx.cond());
            ctx.stmt().forEach(this::visit);
            return null;
        } else if (ctx.ELSE() != null) {
            ctx.stmt().forEach(this::visit);
            return null;
        } else if (ctx.block() != null) {
            visit(ctx.block());
            return null;
        } else if (ctx.exp() != null) {
            visit(ctx.exp());
            return null;
        } 
        return null;
    }

    @Override
    public Void visitExp(SysYParser.ExpContext ctx) {
        if (ctx.IDENT() != null && ctx.L_PAREN() != null) {
            if (!curScope.contains(ctx.IDENT().getText())) {
                errors.add("Error type 2 at Line " + ctx.IDENT().getSymbol().getLine() + ": "
                        + "Undefined function: " + ctx.IDENT().getText());
            } else if (!(curScope.getType(ctx.IDENT().getText()) instanceof FunctionType)) {
                errors.add("Error type 10 at Line " + ctx.IDENT().getSymbol().getLine() + ": "
                        + "Not a function: " + ctx.IDENT().getText());
            } else {
                paramsStack.push(new ArrayList<>());
                ArrayList<Type> tyList = ((FunctionType) curScope.getType(ctx.IDENT().getText())).getParamTypes();
                if (ctx.funcRParams() != null) visit(ctx.funcRParams());
                ArrayList<Type> _paramsTyList = new ArrayList<>(paramsStack.peek());
                if (flag1) {
                    if (tyList.size() != _paramsTyList.size()) {
                        errors.add("Error type 8 at Line " + ctx.IDENT().getSymbol().getLine() + ": "
                                + "Function call with wrong number of parameters.");
                    } else {
                        for (int i = 0; i < tyList.size(); i++) {
                            if (_paramsTyList.get(i).getTypeName().equals("INT")) {
                                if (!tyList.get(i).getTypeName().equals("INT")) {
                                    errors.add("Error type 8 at Line " + ctx.IDENT().getSymbol().getLine() + ": "
                                            + "Function is not applicable for arguments.");
                                    break;
                                }
                            } else if (_paramsTyList.get(i).getTypeName().equals("ARRAY")) {
                                if (!(tyList.get(i).getTypeName().equals("ARRAY") && ((ArrayType) tyList.get(i))
                                        .getCount() == ((ArrayType) _paramsTyList.get(i)).getCount())) {
                                    errors.add("Error type 8 at Line " + ctx.IDENT().getSymbol().getLine() + ": "
                                            + "Function is not applicable for arguments.");
                                    break;
                                }
                            } else {
                                errors.add("Error type 8 at Line " + ctx.IDENT().getSymbol().getLine() + ": "
                                        + "Function is not applicable for arguments.");
                                break; 
                            }
                        }
                    }
                }
                paramsStack.pop();
            }
            flag1 = true;
            return null;
        } else if (ctx.lVal() != null) {
            visit(ctx.lVal());
            return null;
        } else if (ctx.L_PAREN() != null && ctx.exp().size() == 1) {
            visit(ctx.exp(0));
            return null;
        } else if (ctx.MUL() != null || ctx.MINUS() != null || ctx.DIV() != null || ctx.PLUS() != null
                || ctx.MOD() != null) {
            forOp = true;
            visit(ctx.exp(0));
            if (forOp) visit(ctx.exp(1));
            // forOp = false;
            return null;
        } else if (ctx.unaryOp() != null) {
            forOp = true;
            visit(ctx.exp(0));
            return null;
        } else if (ctx.number() != null) {
            forOp = true;
            return null;
        }
        return null;
    }
    
    @Override
    public Void visitLVal(SysYParser.LValContext ctx) {
        String lValName = ctx.IDENT().getText();
        if (!curScope.contains(lValName)) {
            errors.add("Error type 1 at Line " + ctx.IDENT().getSymbol().getLine() + ": "
                    + "Undefined variable: " + lValName);
            if (forOp) forOp = false;
            return null;
        }
        if (curScope.getType(lValName) instanceof FunctionType) {
            if (ctx.exp().size() != 0) {
                errors.add("Error type 9 at Line " + ctx.IDENT().getSymbol().getLine() + ": "
                        + "Not an array: " + lValName);
                if (forOp) forOp = false;
            } else if (forOp) {
                errors.add("Error type 6 at Line " + ctx.IDENT().getSymbol().getLine() + ": "
                        + "Type mismatched for operands.");
                forOp = false;
            }
            return null;
        } else if (curScope.getType(lValName) instanceof ArrayType) {
            int lValDim = ((ArrayType) curScope.getType(lValName)).getCount() - ctx.exp().size();
            if (lValDim > 0) {
                if (forOp) {
                    errors.add("Error type 6 at Line " + ctx.IDENT().getSymbol().getLine() + ": "
                            + "Type mismatched for operands.");
                    forOp = false;
                }
            } else if (lValDim < 0) {
                errors.add("Error type 9 at Line " + ctx.IDENT().getSymbol().getLine() + ": "
                        + "Not an array: " + lValName);
                if (forOp) forOp = false;
            }
            return null;
        } else {
            if (ctx.exp().size() != 0) {
                errors.add("Error type 9 at Line " + ctx.IDENT().getSymbol().getLine() + ": "
                        + "Not an array: " + lValName);
                if (forOp) forOp = false;
            }
            return null;
        }
    }

    @Override
    public Void visitFuncRParams(SysYParser.FuncRParamsContext ctx) {
        for (SysYParser.ParamContext param : ctx.param()) {
            if (param.exp().lVal() != null) {
                String lValName = param.exp().lVal().IDENT().getText();
                if (!curScope.contains(lValName)) {
                    errors.add("Error type 1 at Line " + param.exp().lVal().IDENT().getSymbol().getLine() + ": "
                            + "Undefined variable: " + lValName);
                    flag1 = false;
                    return null;
                }
                if (curScope.getType(lValName) instanceof IntType) {
                    if (param.exp().lVal().exp().size() != 0) {
                        errors.add("Error type 9 at Line " + param.exp().lVal().IDENT().getSymbol().getLine() + ": "
                                + "Not an array: " + lValName);
                        flag1 = false;
                        return null;
                    }
                    paramsStack.peek().add(IntType.getI32());
                } else if (curScope.getType(lValName) instanceof ArrayType) {
                    int lValDim = ((ArrayType) curScope.getType(lValName)).getCount() - param.exp().lVal().exp().size();
                    if (lValDim == 0) paramsStack.peek().add(IntType.getI32());
                    else if (lValDim < 0) {
                        errors.add("Error type 9 at Line " + param.exp().lVal().IDENT().getSymbol().getLine() + ": "
                                + "Not an array: " + lValName);
                        flag1 = false;
                        return null;
                    } else paramsStack.peek().add(new ArrayType(IntType.getI32(), lValDim));
                } else {
                    if (param.exp().lVal().exp().size() != 0) {
                        errors.add("Error type 9 at Line " + param.exp().lVal().IDENT().getSymbol().getLine() + ": "
                                + "Not an array: " + lValName);
                        flag1 = false;
                        return null;
                    }
                    paramsStack.peek().add(curScope.getType(lValName));
                }
            } else if (param.exp().number() != null) {
                paramsStack.peek().add(IntType.getI32());
            } else if (param.exp().L_PAREN() != null && param.exp().IDENT() != null) {
                String funcName = param.exp().IDENT().getText();
                // System.out.println(funcName + " " + param.exp().IDENT().getSymbol().getLine());
                if (!curScope.contains(funcName)) {
                    errors.add("Error type 2 at Line " + param.exp().IDENT().getSymbol().getLine() + ": "
                            + "Undefined function: " + funcName);
                    flag1 = false;
                    return null;
                }
                paramsStack.peek().add(((FunctionType) curScope.getType(funcName)).getRetType());
                visit(param.exp());
            } else if (param.exp().unaryOp() != null || param.exp().MUL() != null || param.exp().MINUS() != null ||
                    param.exp().DIV() != null || param.exp().PLUS() != null || param.exp().MOD() != null) {
                visit(param.exp());
                paramsStack.peek().add(IntType.getI32());
            } else if (param.exp().L_PAREN() != null && param.exp().exp().size() == 1) {
                paramsStack.peek().add(IntType.getI32());
                visit(param.exp().exp(0));
            } else {
                visit(param.exp());
            }
        }
        return null;
    }

    @Override
    public Void visitCond(SysYParser.CondContext ctx) {
        forOp = true;
        if (ctx.exp() != null)
            visit(ctx.exp());
        else if (ctx.cond() != null && forOp) {
            visit(ctx.cond(0));
            if (forOp && ctx.cond(1) != null)
                visit(ctx.cond(1));
        }
        return null;
    }

    public ArrayList<String> getErrors() {
        return errors;
    }
}
