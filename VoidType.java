public class VoidType extends Type {
    private static VoidType voidTypeInstance;

    private VoidType() {
    }

    public static VoidType getVoidType() {
        if (voidTypeInstance == null) {
            voidTypeInstance = new VoidType();
        }
        return voidTypeInstance;
    }

    @Override
    public String getTypeName() {
        return "VOID";
    }
}
