import java.util.ArrayList;

public class FunctionType extends Type{
    private Type retType;
    private ArrayList<Type> paramTypes;

    public FunctionType(Type retType, ArrayList<Type> paramTypes) {
        this.retType = retType;
        this.paramTypes = new ArrayList<>(paramTypes);
    }

    public Type getRetType() {
        return retType;
    }

    public ArrayList<Type> getParamTypes() {
        return paramTypes;
    }

    @Override
    public String getTypeName() {
        return "FUNCTION";
    }
}
