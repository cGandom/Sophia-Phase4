package main.visitor.codeGenerator;

import main.ast.nodes.Program;
import main.ast.nodes.declaration.classDec.ClassDeclaration;
import main.ast.nodes.declaration.classDec.classMembersDec.ConstructorDeclaration;
import main.ast.nodes.declaration.classDec.classMembersDec.FieldDeclaration;
import main.ast.nodes.declaration.classDec.classMembersDec.MethodDeclaration;
import main.ast.nodes.declaration.variableDec.VarDeclaration;
import main.ast.nodes.expression.*;
import main.ast.nodes.expression.operators.BinaryOperator;
import main.ast.nodes.expression.operators.UnaryOperator;
import main.ast.nodes.expression.values.ListValue;
import main.ast.nodes.expression.values.NullValue;
import main.ast.nodes.expression.values.primitive.BoolValue;
import main.ast.nodes.expression.values.primitive.IntValue;
import main.ast.nodes.expression.values.primitive.StringValue;
import main.ast.nodes.statement.*;
import main.ast.nodes.statement.loop.BreakStmt;
import main.ast.nodes.statement.loop.ContinueStmt;
import main.ast.nodes.statement.loop.ForStmt;
import main.ast.nodes.statement.loop.ForeachStmt;
import main.ast.types.NullType;
import main.ast.types.Type;
import main.ast.types.functionPointer.FptrType;
import main.ast.types.list.ListNameType;
import main.ast.types.list.ListType;
import main.ast.types.single.BoolType;
import main.ast.types.single.ClassType;
import main.ast.types.single.IntType;
import main.ast.types.single.StringType;
import main.symbolTable.SymbolTable;
import main.symbolTable.exceptions.ItemNotFoundException;
import main.symbolTable.items.ClassSymbolTableItem;
import main.symbolTable.items.FieldSymbolTableItem;
import main.symbolTable.utils.graph.Graph;
import main.visitor.Visitor;
import main.visitor.typeChecker.ExpressionTypeChecker;

import java.io.*;
import java.util.ArrayList;

public class CodeGenerator extends Visitor<String> {
    ExpressionTypeChecker expressionTypeChecker;
    Graph<String> classHierarchy;
    private String outputPath;
    private FileWriter currentFile;
    private ClassDeclaration currentClass;
    private MethodDeclaration currentMethod;

    private Integer labelCounter;
    private ArrayList<ArrayList<String>> labelsStack;

    private ArrayList<String> currentSlots;
    private int tempVarNumber;

    public CodeGenerator(Graph<String> classHierarchy) {
        this.classHierarchy = classHierarchy;
        this.expressionTypeChecker = new ExpressionTypeChecker(classHierarchy);
        this.labelsStack = new ArrayList<>();
        this.prepareOutputFolder();
    }

    private void prepareOutputFolder() {
        this.outputPath = "output/";
        String jasminPath = "utilities/jarFiles/jasmin.jar";
        String listClassPath = "utilities/codeGenerationUtilityClasses/List.j";
        String fptrClassPath = "utilities/codeGenerationUtilityClasses/Fptr.j";
        try{
            File directory = new File(this.outputPath);
            File[] files = directory.listFiles();
            if(files != null)
                for (File file : files)
                    file.delete();
            directory.mkdir();
        }
        catch(SecurityException e) { }
        copyFile(jasminPath, this.outputPath + "jasmin.jar");
        copyFile(listClassPath, this.outputPath + "List.j");
        copyFile(fptrClassPath, this.outputPath + "Fptr.j");
    }

    private void copyFile(String toBeCopied, String toBePasted) {
        try {
            File readingFile = new File(toBeCopied);
            File writingFile = new File(toBePasted);
            InputStream readingFileStream = new FileInputStream(readingFile);
            OutputStream writingFileStream = new FileOutputStream(writingFile);
            byte[] buffer = new byte[1024];
            int readLength;
            while ((readLength = readingFileStream.read(buffer)) > 0)
                writingFileStream.write(buffer, 0, readLength);
            readingFileStream.close();
            writingFileStream.close();
        } catch (IOException e) { }
    }

    private void createFile(String name) {
        try {
            String path = this.outputPath + name + ".j";
            File file = new File(path);
            file.createNewFile();
            FileWriter fileWriter = new FileWriter(path);
            this.currentFile = fileWriter;
        } catch (IOException e) {}
    }

    private void addCommand(String command) {
        try {
            command = String.join("\n\t\t", command.split("\n"));
            if(command.startsWith("Label_"))
                this.currentFile.write("\t" + command + "\n");
            else if(command.startsWith("."))
                this.currentFile.write(command + "\n");
            else
                this.currentFile.write("\t\t" + command + "\n");
            this.currentFile.flush();
        } catch (IOException e) {}
    }

    private void addBlankLine() {
        try {
            this.currentFile.write("\n");
            this.currentFile.flush();
        } catch (IOException ignored) {}
    }

    private void pushLabels(String nAfter, String nBrk, String nCont) {
        ArrayList<String> newLabels = new ArrayList<>(3);
        newLabels.add(nAfter);
        newLabels.add(nBrk);
        newLabels.add(nCont);
        this.labelsStack.add(newLabels);
    }

    private ArrayList<String> getTopLabels() {
        return this.labelsStack.get(this.labelsStack.size() - 1);
    }

    private String getTopAfterLabel() {
        return this.labelsStack.get(this.labelsStack.size() - 1).get(0);
    }

    private String getTopBrkLabel() {
        return this.labelsStack.get(this.labelsStack.size() - 1).get(1);
    }

    private String getTopContLabel() {
        return this.labelsStack.get(this.labelsStack.size() - 1).get(2);
    }

    private void popLabels() {
        this.labelsStack.remove(this.labelsStack.size() - 1);
    }

    private String getNewLabel(){
        String newLabel = "Label" + labelCounter.toString();
        labelCounter += 1;
        return newLabel;
    }

    private String underlineOrSpace(int slot) {
        if (0 <= slot && slot <= 3)
            return "_";
        else
            return " ";
    }

    private void branch(Expression exp, String nTrue, String nFalse){
        if (exp instanceof UnaryExpression) {
            UnaryExpression unExp = (UnaryExpression) exp;
            if (unExp.getOperator() == UnaryOperator.not){
                branch(exp, nFalse, nTrue);
                return;
            }
        }
        if (exp instanceof BinaryExpression) {
            BinaryExpression binExp = (BinaryExpression) exp;
            if (binExp.getBinaryOperator() == BinaryOperator.and) {
                String nNext = getNewLabel();
                branch(binExp.getFirstOperand(), nNext, nFalse);
                addCommand(nNext + ":");
                branch(binExp.getSecondOperand(), nTrue, nFalse);
                return;
            }
            else if (binExp.getBinaryOperator() == BinaryOperator.or) {
                String nNext = getNewLabel();
                branch(binExp.getFirstOperand(), nTrue, nNext);
                addCommand(nNext + ":");
                branch(binExp.getSecondOperand(), nTrue, nFalse);
                return;
            }
        }
        if (exp instanceof BoolValue) {
            BoolValue boolValue = (BoolValue) exp;
            if (boolValue.getConstant())
                addCommand("goto " + nTrue);
            else
                addCommand("goto " + nFalse);
            return;
        }
        String expCommand = exp.accept(this);
        addCommand(expCommand);
        addCommand("ifeq " + nFalse);
        addCommand("goto " + nTrue);
    }

    private String makeTypeSignature(Type t) {
        String signature = "";
        if (t instanceof IntType)
            signature += "Ljava/lang/Integer";
        else if (t instanceof BoolType)
            signature += "Ljava/lang/Boolean";
        else if (t instanceof StringType)
            signature += "Ljava/lang/String";
        else if (t instanceof ListType)
            signature += "LList;";
        else if (t instanceof FptrType)
            signature += "LFptr;";
        else if (t instanceof ClassType)
            signature += "L" + ((ClassType) t).getClassName().getName() + ";";
        return signature;
    }

    private String makeFuncArgsSignature(ArrayList<Type> argsType) {
        String signature = "";
        for (Type type : argsType) {
            signature += makeTypeSignature(type);
        }
        return signature;
    }

    private void initFieldDeclaration(FieldDeclaration fieldDeclaration) {
        Type fieldType = fieldDeclaration.getVarDeclaration().getType();
        if (fieldType instanceof IntType) {
            addCommand("new java/lang/Integer");
            addCommand("dup");
            addCommand("ldc 0");
            addCommand("invokespecial java/lang/Integer/<init>(I)V");
        }
        else if (fieldType instanceof BoolType) {
            addCommand("new java/lang/Boolean");
            addCommand("dup");
            addCommand("ldc false");
            addCommand("invokespecial java/lang/Boolean/<init>(Z)V");
        }
        else if (fieldType instanceof StringType) {
            addCommand("new java/lang/String");
            addCommand("dup");
            addCommand("ldc \"\"");
            addCommand("invokespecial java/lang/String/<init>(Ljava/lang/String;)V"); //maybe wrong
        }
        else if (fieldType instanceof ListType) {
            ArrayList<ListNameType> listNameTypes = ((ListType)fieldType).getElementsTypes();
            addCommand("new List");
            addCommand("dup ");
            for (ListNameType listNameType : listNameTypes) { //todo

            }
        }
        else if (fieldType instanceof FptrType) {
            addCommand("ldc null");
        }
        else if (fieldType instanceof ClassType) {
            addCommand("ldc null");
        }
    }

    private void addDefaultConstructor() {
        addCommand(".method public <init>()V");

        addCommand("aload_0");
        if (this.currentClass.getParentClassName() != null)
            addCommand("invokespecial " + this.currentClass.getParentClassName().getName() + "/<init>()V");
        else
            addCommand("invokespecial java/lang/Object/<init>()V");

        for (FieldDeclaration fieldDeclaration : this.currentClass.getFields()) {
            addCommand("aload_0");
            initFieldDeclaration(fieldDeclaration);
            addCommand("putfield " + this.currentClass.getClassName().getName()
                    + "/" + fieldDeclaration.getVarDeclaration().getVarName().getName()
                    + " " + makeTypeSignature(fieldDeclaration.getVarDeclaration().getType()));
        }

        addCommand("return");
        addCommand(".end method");
    }

    private void addStaticMainMethod() {
        //todo
    }

    private int slotOf(String identifier) {
        if (identifier.equals("")) {
            this.tempVarNumber++;
            return this.currentSlots.size() + this.tempVarNumber;
        }
        for (int i = 0; i < this.currentSlots.size(); i++) {
            if (this.currentSlots.get(i).equals(identifier))
                return i;
        }
        return 0;
    }

    @Override
    public String visit(Program program) {
        for (ClassDeclaration sophiaClass : program.getClasses()) {
            createFile(sophiaClass.getClassName().getName());
            sophiaClass.accept(this);
        }
        return null;
    }

    @Override
    public String visit(ClassDeclaration classDeclaration) {
        this.currentClass = classDeclaration;
        this.expressionTypeChecker.setCurrentClass(classDeclaration);

        addCommand(".class public " + classDeclaration.getClassName().getName());
        if (classDeclaration.getParentClassName() == null)
            addCommand(".super java/lang/Object");
        else
            addCommand(".super " + classDeclaration.getParentClassName().getName());
        addBlankLine();

        addDefaultConstructor();
        if (classDeclaration.getConstructor() != null)
            classDeclaration.getConstructor().accept(this);
        addBlankLine();

        for (FieldDeclaration fieldDeclaration : classDeclaration.getFields()) {
            fieldDeclaration.accept(this);
        }
        addBlankLine();

        for (MethodDeclaration methodDeclaration : classDeclaration.getMethods()) {
            methodDeclaration.accept(this);
        }
        return null;
    }

    @Override
    public String visit(ConstructorDeclaration constructorDeclaration) {
        //todo add default constructor or static main method if needed
        this.visit((MethodDeclaration) constructorDeclaration);
        return null;
    }

    @Override
    public String visit(MethodDeclaration methodDeclaration) {
        this.currentMethod = methodDeclaration;
        this.expressionTypeChecker.setCurrentMethod(methodDeclaration);
        this.labelCounter = 0;
        this.labelsStack.clear();
        this.currentSlots.clear();
        this.currentSlots.add("this");

        //todo add method or constructor headers
        if(methodDeclaration instanceof ConstructorDeclaration) {
            //todo call parent constructor
            //todo initialize fields
        }
        for (VarDeclaration varDeclaration : methodDeclaration.getLocalVars()) {
            varDeclaration.accept(this);
        }

        for (Statement statement : methodDeclaration.getBody()) {
            String nAfter = getNewLabel();
            pushLabels(nAfter, nAfter, nAfter);
            statement.accept(this);
            popLabels();
            addCommand(nAfter + ":");
        }
        if (methodDeclaration.getReturnType() instanceof NullType) {
            addCommand("return");
        }
        addCommand(".end method");
        return null;
    }

    @Override
    public String visit(FieldDeclaration fieldDeclaration) {
        addCommand(".field public " + fieldDeclaration.getVarDeclaration().getVarName().getName() + " "
                + makeTypeSignature(fieldDeclaration.getVarDeclaration().getType()));
        return null;
    }

    @Override
    public String visit(VarDeclaration varDeclaration) {
        this.currentSlots.add(varDeclaration.getVarName().getName());
        return null;
    }

    @Override
    public String visit(AssignmentStmt assignmentStmt) {
        BinaryExpression assignmentExpression = new BinaryExpression(assignmentStmt.getlValue(),
                assignmentStmt.getrValue(), BinaryOperator.assign);
        addCommand(assignmentExpression.accept(this));
        addCommand("pop");
        return null;
    }

    @Override
    public String visit(BlockStmt blockStmt) {
        for (Statement statement : blockStmt.getStatements()) {
            String nAfter = getNewLabel();
            pushLabels(nAfter, nAfter, nAfter);
            statement.accept(this);
            popLabels();
            addCommand(nAfter + ":");
        }
        return null;
    }

    @Override
    public String visit(ConditionalStmt conditionalStmt) {
        String nTrue = getNewLabel();
        String nFalse = getNewLabel();
        branch(conditionalStmt.getCondition(), nTrue, nFalse);

        addCommand(nTrue + ":");
        pushLabels(getTopAfterLabel(), getTopBrkLabel(), getTopContLabel());
        conditionalStmt.getThenBody().accept(this);
        popLabels();

        addCommand(nFalse + ":");
        pushLabels(getTopAfterLabel(), getTopBrkLabel(), getTopContLabel());
        conditionalStmt.getThenBody().accept(this);
        popLabels();

        return null;
    }

    @Override
    public String visit(MethodCallStmt methodCallStmt) {
        //todo
        return null;
    }

    @Override
    public String visit(PrintStmt print) {
        //todo
        return null;
    }

    @Override
    public String visit(ReturnStmt returnStmt) {
        Type type = returnStmt.getReturnedExpr().accept(expressionTypeChecker);
        if(type instanceof NullType) {
            addCommand("return");
        }
        else {
            //todo add commands to return
        }
        return null;
    }

    @Override
    public String visit(BreakStmt breakStmt) {
        addCommand("goto " + getTopBrkLabel());
        return null;
    }

    @Override
    public String visit(ContinueStmt continueStmt) {
        addCommand("goto " + getTopContLabel());
        return null;
    }

    @Override
    public String visit(ForeachStmt foreachStmt) {
        //todo
        return null;
    }

    @Override
    public String visit(ForStmt forStmt) {
        //todo
        return null;
    }

    @Override
    public String visit(BinaryExpression binaryExpression) {
        BinaryOperator operator = binaryExpression.getBinaryOperator();
        String commands = "";
        if (operator == BinaryOperator.add) {
            //todo
        }
        else if (operator == BinaryOperator.sub) {
            //todo
        }
        else if (operator == BinaryOperator.mult) {
            //todo
        }
        else if (operator == BinaryOperator.div) {
            //todo
        }
        else if (operator == BinaryOperator.mod) {
            //todo
        }
        else if((operator == BinaryOperator.gt) || (operator == BinaryOperator.lt)) {
            //todo
        }
        else if((operator == BinaryOperator.eq) || (operator == BinaryOperator.neq)) {
            //todo
        }
        else if(operator == BinaryOperator.and) {
            //todo
        }
        else if(operator == BinaryOperator.or) {
            //todo
        }
        else if(operator == BinaryOperator.assign) {
            Type firstType = binaryExpression.getFirstOperand().accept(expressionTypeChecker);
            String secondOperandCommands = binaryExpression.getSecondOperand().accept(this);
            if(firstType instanceof ListType) {
                //todo make new list with List copy constructor with the second operand commands
                // (add these commands to secondOperandCommands)
            }
            if(binaryExpression.getFirstOperand() instanceof Identifier) {
                //todo
            }
            else if(binaryExpression.getFirstOperand() instanceof ListAccessByIndex) {
                //todo
            }
            else if(binaryExpression.getFirstOperand() instanceof ObjectOrListMemberAccess) {
                Expression instance = ((ObjectOrListMemberAccess) binaryExpression.getFirstOperand()).getInstance();
                Type memberType = binaryExpression.getFirstOperand().accept(expressionTypeChecker);
                String memberName = ((ObjectOrListMemberAccess) binaryExpression.getFirstOperand()).getMemberName().getName();
                Type instanceType = instance.accept(expressionTypeChecker);
                if(instanceType instanceof ListType) {
                    //todo
                }
                else if(instanceType instanceof ClassType) {
                    //todo
                }
            }
        }
        return commands;
    }

    @Override
    public String visit(UnaryExpression unaryExpression) {
        UnaryOperator operator = unaryExpression.getOperator();
        String commands = "";
        if(operator == UnaryOperator.minus) {
            //todo
        }
        else if(operator == UnaryOperator.not) {
            //todo
        }
        else if((operator == UnaryOperator.predec) || (operator == UnaryOperator.preinc)) {
            if(unaryExpression.getOperand() instanceof Identifier) {
                //todo
            }
            else if(unaryExpression.getOperand() instanceof ListAccessByIndex) {
                //todo
            }
            else if(unaryExpression.getOperand() instanceof ObjectOrListMemberAccess) {
                Expression instance = ((ObjectOrListMemberAccess) unaryExpression.getOperand()).getInstance();
                Type memberType = unaryExpression.getOperand().accept(expressionTypeChecker);
                String memberName = ((ObjectOrListMemberAccess) unaryExpression.getOperand()).getMemberName().getName();
                Type instanceType = instance.accept(expressionTypeChecker);
                if(instanceType instanceof ListType) {
                    //todo
                }
                else if(instanceType instanceof ClassType) {
                    //todo
                }
            }
        }
        else if((operator == UnaryOperator.postdec) || (operator == UnaryOperator.postinc)) {
            if(unaryExpression.getOperand() instanceof Identifier) {
                //todo
            }
            else if(unaryExpression.getOperand() instanceof ListAccessByIndex) {
                //todo
            }
            else if(unaryExpression.getOperand() instanceof ObjectOrListMemberAccess) {
                Expression instance = ((ObjectOrListMemberAccess) unaryExpression.getOperand()).getInstance();
                Type memberType = unaryExpression.getOperand().accept(expressionTypeChecker);
                String memberName = ((ObjectOrListMemberAccess) unaryExpression.getOperand()).getMemberName().getName();
                Type instanceType = instance.accept(expressionTypeChecker);
                if(instanceType instanceof ListType) {
                    //todo
                }
                else if(instanceType instanceof ClassType) {
                    //todo
                }
            }
        }
        return commands;
    }

    @Override
    public String visit(ObjectOrListMemberAccess objectOrListMemberAccess) {
        Type memberType = objectOrListMemberAccess.accept(expressionTypeChecker);
        Type instanceType = objectOrListMemberAccess.getInstance().accept(expressionTypeChecker);
        String memberName = objectOrListMemberAccess.getMemberName().getName();
        String commands = "";
        if(instanceType instanceof ClassType) {
            String className = ((ClassType) instanceType).getClassName().getName();
            try {
                SymbolTable classSymbolTable = ((ClassSymbolTableItem) SymbolTable.root.getItem(ClassSymbolTableItem.START_KEY + className, true)).getClassSymbolTable();
                try {
                    classSymbolTable.getItem(FieldSymbolTableItem.START_KEY + memberName, true);
                    commands += objectOrListMemberAccess.getInstance().accept(this);
                    commands += "getfield " + className + "/" + memberName + " " + makeTypeSignature(memberType) + "\n";
                } catch (ItemNotFoundException memberIsMethod) {
                    commands += "new Fptr\n";
                    commands += "dup\n";
                    commands += objectOrListMemberAccess.getInstance().accept(this);
                    commands += "ldc " + memberName + "\n";
                    commands += "invokespecial Fptr/<init>(Ljava/lang/Object;Ljava/lang/String;)V\n";
                }
            } catch (ItemNotFoundException classNotFound) {
            }
        }
        else if(instanceType instanceof ListType) {
            ListType listType = (ListType) instanceType;
            int index = 0;
            for (index = 0; index < listType.getElementsTypes().size(); index++) {
                if (listType.getElementsTypes().get(index).getName().getName().equals(memberName))
                    break;
            }
            commands += objectOrListMemberAccess.getInstance().accept(this);
            commands += "ldc " + index + "\n";
            commands += "invokevirtual List/getElement(I)Ljava/lang/Object;\n";
        }
        return commands;
    }

    @Override
    public String visit(Identifier identifier) {
        String commands = "";
        int slot = slotOf(identifier.getName());
        commands += "aload" + underlineOrSpace(slot) + slot + "\n";
        //todo cast to primitive int and bool
        return commands;
    }

    @Override
    public String visit(ListAccessByIndex listAccessByIndex) {
        String commands = "";
        commands += listAccessByIndex.getInstance().accept(this);
        commands += listAccessByIndex.getIndex().accept(this);
        //todo cast to primitive
        commands += "invokevirtual List/getElement(I)Ljava/lang/Object;\n";
        return commands;
    }

    @Override
    public String visit(MethodCall methodCall) {
        String commands = "";
        commands += methodCall.getInstance().accept(this);
        commands += "new java/util/ArrayList\n";
        commands += "dup\n";
        commands += "invokespecial java/util/ArrayList/<init>()V\n";
        for (Expression methodArgs : methodCall.getArgs()) {
            commands += methodArgs.accept(this);
            //todo check for int bool casting
            commands += "invokevirtual java/util/ArrayList/add(Ljava/lang/Object;)Z\n";
            commands += "pop\n";
        }
        commands += "invokevirtual List/invoke(Ljava/util/ArrayList;)Ljava/lang/Object;\n";
        return commands;
    }

    @Override
    public String visit(NewClassInstance newClassInstance) {
        String commands = "";
        commands += "new " + newClassInstance.getClassType().getClassName().getName() + "\n";
        commands += "dup\n";

        for (Expression arg : newClassInstance.getArgs()) {
            commands += arg.accept(this);
            //todo check if its int or bool and should be casted to Integer and Boolean
        }
        try {
            ClassDeclaration classDeclaration = ((ClassSymbolTableItem) SymbolTable.root.getItem(ClassSymbolTableItem.START_KEY
                    + newClassInstance.getClassType().getClassName().getName(), true)).getClassDeclaration();
            ArrayList<Type> classConstructorArgTypes = new ArrayList<>();
            if (classDeclaration.getConstructor() != null) {
                for (VarDeclaration argDec : classDeclaration.getConstructor().getArgs())
                    classConstructorArgTypes.add(argDec.getType());
            }
            commands += "invokespecial " + newClassInstance.getClassType().getClassName()
                    + "/<init>(" + makeFuncArgsSignature(classConstructorArgTypes) + ")V\n";
        } catch (ItemNotFoundException ignored) {}
        return commands;
    }

    @Override
    public String visit(ThisClass thisClass) {
        String commands = "";
        commands += "aload_0";
        return commands;
    }

    @Override
    public String visit(ListValue listValue) {
        String commands = "";
        commands += "new List\n";
        commands += "dup\n";

        commands += "new java/util/ArrayList\n";
        commands += "dup\n";
        for (Expression expr : listValue.getElements()) {
            commands += expr.accept(this);
            //todo check for primitive casting (bool and int)
        }
        commands += "invokespecial java/util/ArrayList/<init>()V\n";

        commands += "invokespecial List/<init>(Ljava/util/ArrayList;)V\n";
        return commands;
    }

    @Override
    public String visit(NullValue nullValue) {
        String commands = "";
        commands += "ldc null\n";
        return commands;
    }

    @Override
    public String visit(IntValue intValue) {
        String commands = "";
        commands += "ldc " + intValue.getConstant() + "\n";
        return commands;
    }

    @Override
    public String visit(BoolValue boolValue) {
        String commands = "";
        if (boolValue.getConstant())
            commands += "ldc 1\n";
        else
            commands += "ldc 0\n";
        return commands;
    }

    @Override
    public String visit(StringValue stringValue) {
        String commands = "";
        commands += "ldc \"" + stringValue.getConstant() + "\"\n";
        return commands;
    }

}