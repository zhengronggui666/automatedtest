import com.ibm.wala.classLoader.Language;
import com.ibm.wala.classLoader.ShrikeBTMethod;
import com.ibm.wala.ipa.callgraph.*;
import com.ibm.wala.ipa.callgraph.impl.AllApplicationEntrypoints;
import com.ibm.wala.ipa.callgraph.impl.Util;
import com.ibm.wala.ipa.callgraph.propagation.SSAPropagationCallGraphBuilder;
import com.ibm.wala.ipa.cha.ClassHierarchy;
import com.ibm.wala.ipa.cha.ClassHierarchyException;
import com.ibm.wala.ipa.cha.ClassHierarchyFactory;
import com.ibm.wala.shrikeCT.InvalidClassFileException;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.types.annotations.Annotation;
import com.ibm.wala.util.CancelException;
import com.ibm.wala.util.config.AnalysisScopeReader;
import com.ibm.wala.util.io.FileProvider;

import java.io.*;
import java.util.*;


public class WALATest {
    static Map<String, Set<String>> preClassMap;//key:类名，value:前驱类名
    static Map<String, Set<String>> preMethodMap;//key:类名+方法签名，value: 前驱类名+前驱方法签名
    static Map<String, Set<String>> classMethodMap;//key:类名,value:类名+方法签名
    static Map<String, ShrikeBTMethod> BTMethodMap;//key:类名+方法签名, value:对应的ShrikeBTMethod
    static String command;
    static String project_target;
    static String change_info_path;

    static Set<String> temps = new HashSet<>();


    public static void main(String[] args) throws IOException, ClassHierarchyException, InvalidClassFileException, CancelException {
        command=args[0];
        project_target=args[1];
        change_info_path=args[2];
        File exFile = new FileProvider().getFile("exclusion.txt");
        AnalysisScope scope = AnalysisScopeReader.readJavaScope("scope.txt", exFile, ClassLoader.getSystemClassLoader());

        //找到target目录下所有.class文件
        List<File> fileList = new ArrayList<>();
        findFiles(project_target, fileList);

        //将class文件加入scope
        for (int i = 0; i < fileList.size(); i++) {
            scope.addClassFileToScope(ClassLoaderReference.Application, fileList.get(i));
        }

        //数据结构初始化
        preClassMap = new HashMap<>();
        preMethodMap = new HashMap<>();
        classMethodMap = new HashMap<>();
        BTMethodMap = new HashMap<>();

        // 1.生成类层次关系对象
        ClassHierarchy cha = ClassHierarchyFactory.makeWithRoot(scope);
        // 2.生成进入点
        AllApplicationEntrypoints entrypoints = new AllApplicationEntrypoints(scope, cha);
        AnalysisOptions option = new AnalysisOptions(scope, entrypoints);
        SSAPropagationCallGraphBuilder builder = Util.makeZeroCFABuilder(
                Language.JAVA, option, new AnalysisCacheImpl(), cha, scope);
        //3.生成图
        CallGraph cg = builder.makeCallGraph(option);


        // 4.遍历cg中所有的节点
        for (CGNode node : cg) {

            // node中包含了很多信息，包括类加载器、方法信息等，这里只筛选出需要的信息
            if (node.getMethod() instanceof ShrikeBTMethod) {
                //node.getMethod()返回一个比较泛化的IMethod实例，不能获取到我们想要的信息
                //一般地，本项目中所有和业务逻辑相关的方法都是ShrikeBTMethod对象
                ShrikeBTMethod method = (ShrikeBTMethod) node.getMethod();
                // 使用Primordial类加载器加载的类都属于Java原生类，我们一般不关心。
                if ("Application".equals(method.getDeclaringClass().getClassLoader().toString())) {
                    // 获取声明该方法的类的内部表示
                    String classInnerName = method.getDeclaringClass().getName().toString();
                    preClassMap.putIfAbsent(classInnerName, new HashSet<>());
                    classMethodMap.putIfAbsent(classInnerName, new HashSet<>());

                    // 获取方法签名
                    String signature = method.getSignature();
                    String class_signature = classInnerName + " " + signature;
                    preMethodMap.putIfAbsent(class_signature, new HashSet<>());
                    BTMethodMap.putIfAbsent(class_signature, method);
                    classMethodMap.get(classInnerName).add(classInnerName + " " + signature);
                    System.out.println(classInnerName + " " + signature);
                    Iterator<CGNode> preIt = cg.getPredNodes(node);
                    while (preIt.hasNext()) {
                        CGNode preNode = preIt.next();
                        if (preNode.getMethod() instanceof ShrikeBTMethod) {
                            ShrikeBTMethod preMethod = (ShrikeBTMethod) preNode.getMethod();
                            // 使用Primordial类加载器加载的类都属于Java原生类，我们一般不关心。
                            if ("Application".equals(preMethod.getDeclaringClass().getClassLoader().toString())) {

                                // 获取声明该方法的类的内部表示
                                String preClass = preMethod.getDeclaringClass().getName().toString();
                                preClassMap.get(classInnerName).add(preClass);
                                // 获取前驱方法签名
                                String preSignature = preMethod.getSignature();
                                Collection<Annotation> annotations = preMethod.getAnnotations();
                                preMethodMap.get(class_signature).add(preClass + " " + preSignature);

                            }
                        }
                    }
                }
            } else {
                System.out.println(String.format("'%s'不是一个ShrikeBTMethod：%s", node.getMethod(), node.getMethod().getClass()));
            }
        }
        //去除前驱节点为空的类和方法
        preClassMap.entrySet().removeIf(item -> item.getValue().isEmpty());
        preMethodMap.entrySet().removeIf(item -> item.getValue().isEmpty());
//        printToDot();//生成dot文件
        if(command.equals("-c")){
            classSelect();
        }
        else if(command.equals("-m")){
            methodSelect();
        }

    }

    /**
     * 找到target目录下的所有.class文件
     * @param targetPath   target路径
     * @param fileList      文件列表
     */
    public static void findFiles(String targetPath, List<File> fileList) {

        File dir = new File(targetPath);
        File[] files = dir.listFiles(); // 该文件目录下文件全部放入数组
        if (files != null) {
            for (int i = 0; i < files.length; i++) {
                //是文件夹的话就是要递归再深入查找文件
                if (files[i].isDirectory()) { // 判断是文件还是文件夹
                    findFiles(files[i].getAbsolutePath(), fileList); // 获取文件绝对路径
                } else {
                    //如果是文件，找到.class文件
                    if (files[i].getName().endsWith(".class"))
                        fileList.add(files[i]);
                }
            }
        }
        return;

    }

    /**
     * 类和方法依赖打印到dot文件
     */
    public static void printToDot() {

        try {
            BufferedWriter classOut = new BufferedWriter(new FileWriter("class-DataLog.dot"));
            String classTitle = "digraph cmd_class {\n";
            classOut.write(classTitle);
            for (Map.Entry<String, Set<String>> entry : preClassMap.entrySet()) {
                for (String str : entry.getValue()) {
                    String classLine = "   \"" + entry.getKey() + "\" -> \"" + str + "\";\n";
                    classOut.write(classLine);
                }

            }
            classOut.write("}");
            classOut.close();
            BufferedWriter methodOut = new BufferedWriter(new FileWriter("method-DataLog.dot"));
            String methodTitle = "digraph cmd_method {\n";
            methodOut.write(methodTitle);
            for (Map.Entry<String, Set<String>> entry : preMethodMap.entrySet()) {
                String key = entry.getKey().split(" ")[1];
                for (String str : entry.getValue()) {

                    String value = str.split(" ")[1];
                    String methodLine = "   \"" + key + "\" -> \"" + value + "\";\n";
                    methodOut.write(methodLine);
                }
            }
            methodOut.write("}");
            methodOut.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 类级选择
     */
    public static void classSelect() {
        Set<String> classChange = new HashSet<>();

        //读取change_info.txt找到变更类
        try {
            BufferedReader in = new BufferedReader(new FileReader(change_info_path));
            String str;
            while ((str = in.readLine()) != null) {
                String[] strings = str.split(" ");
                classChange.add(strings[0]);
            }
            System.out.println(str);
        } catch (IOException e) {
            e.printStackTrace();
        }
        //类选择
        try {
            BufferedWriter classSelectionOut = new BufferedWriter(new FileWriter("./selection-class.txt"));
            Set<String> methods = new HashSet<>();
            temps=new HashSet<>();
            //递归找到所有测试方法
            for (String className : classChange) {
                for (String classMethod : classMethodMap.get(className)) {
                    recurPre(classMethod, methods);
                }
            }
            Set<String> temp = new HashSet<>(methods);
            //循环找到受影响的测试类下的所有测试方法
            for(String methodName:temp){
                String className=methodName.split(" ")[0];
                for(String method:classMethodMap.get(className)){
                    boolean flag=false;
                    Collection<Annotation> annotations=BTMethodMap.get(method).getAnnotations();
                    for(Annotation annotation:annotations){
                        if(annotation.getType().toString().equals("<Application,Lorg/junit/Test>")){
                            flag=true;
                            break;
                        }
                    }
                    if(flag){
                        methods.add(method);
                    }
                }


            }
            for (String method : methods) {
                classSelectionOut.write(method + "\n");
            }
            classSelectionOut.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    /**
     * 方法级选择
     */
    public static void methodSelect() {
        Set<String> methodChange = new HashSet<>();
        //读取change_info.txt找到变更方法
        try {
            BufferedReader in = new BufferedReader(new FileReader(change_info_path));
            String str;
            while ((str = in.readLine()) != null) {
                methodChange.add(str);
            }
            System.out.println(str);
        } catch (IOException e) {
            e.printStackTrace();
        }
        //方法选择
        try {

            BufferedWriter methodSelectionOut = new BufferedWriter(new FileWriter("./selection-method.txt"));
            Set<String> methods = new HashSet<>();
            temps=new HashSet<>();
            //递归找到所有受影响的测试方法
            for (String methodName : methodChange) {
                recurPre(methodName, methods);
            }
            for (String methodName : methods) {
                methodSelectionOut.write(methodName + "\n");
            }
            methodSelectionOut.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 递归找到所有受影响的测试方法
     * @param method   方法名
     * @param methods   受影响的测试方法集合
     */
    public static void recurPre(String method, Set<String> methods) {
        if (!preMethodMap.containsKey(method)) {
            return;
        }
        Set<String> methodNames=preMethodMap.get(method);

        for(String methodName:methodNames){
            if(preMethodMap.containsKey(methodName)){
                if(!temps.contains(methodName)){
                    temps.add(methodName);
                    recurPre(methodName,methods);
                }
            }
            else {
                boolean flag=false;
                //判定测试方法
                Collection<Annotation> annotations=BTMethodMap.get(methodName).getAnnotations();
                for(Annotation annotation:annotations){
                    if(annotation.getType().toString().equals("<Application,Lorg/junit/Test>")){
                        flag=true;
                        break;
                    }
                }
                if(BTMethodMap.get(methodName).isInit()||BTMethodMap.get(methodName).isClinit()){
                    flag=false;
                }
                if(flag){
                    methods.add(methodName);
                }

            }
        }
        return;
//递归变循环
//            Set<String> promethods = new HashSet<>();
//            ;
//            for (String methodName : preMethodMap.get(method)) {
//
//                if (preMethodMap.containsKey(methodName)) {
//                    promethods.add(methodName);
//                } else {
//                    methods.add(methodName);
//                }
//            }
//            while (promethods.size() > 0) {
//                for (String a : promethods) {
//                    System.out.println(a);
//                }
//
//                Set<String> temp = new HashSet<>();
//                System.out.println(temp.size());
//                for (String preMethod : promethods) {
//                    for (String methodName : preMethodMap.get(preMethod)) {
//
//                        if (preMethodMap.containsKey(methodName)) {
//                            if (!temps.contains(methodName)) {
//                                temp.add(methodName);
//                                temps.add(methodName);
//                            }
//
//                        } else {
//                            methods.add(methodName);
//                        }
//                    }
//                }
//                for (String a : temp) {
//                    System.out.println(a);
//                }
//                promethods = temp;
//
//                System.out.println(promethods.size());
//
//            }
    }
}






