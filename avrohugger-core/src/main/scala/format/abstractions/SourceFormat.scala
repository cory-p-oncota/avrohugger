package avrohugger
package format
package abstractions

import avrohugger.matchers.TypeMatcher
import avrohugger.models.CompilationUnit
import avrohugger.stores.{ ClassStore, SchemaStore }

import org.apache.avro.{ Protocol, Schema }
import org.apache.avro.Schema.Type.{ ENUM, RECORD }

import java.nio.file.{ Path, Paths, Files, StandardOpenOption }
import java.io.{ File, FileNotFoundException, IOException }

import treehugger.forest._
import definitions._
import treehuggerDSL._

import scala.collection.JavaConversions._

/** Parent to all ouput formats
  *
  * _ABSTRACT MEMBERS_: to be implemented by a subclass
  * asCompilationUnits
  * compile
  * getName
  * scalaTreehugger
  * toolName
  * toolShortDescription
  *
  * _CONCRETE MEMBERS_: implementations to be inherited by a subclass
  * fileExt
  * getFilePath
  * getLocalSubtypes
  * getJavaCompilationUnit
  * getScalaCompilationUnit
  * isEnum
  * registerTypes
  * renameEnum
  * writeToFile
  */
trait SourceFormat {

  ////////////////////////////// abstract members //////////////////////////////
  def asCompilationUnits(
    classStore: ClassStore, 
    namespace: Option[String], 
    schemaOrProtocol: Either[Schema, Protocol],
    schemaStore: SchemaStore,
    maybeOutDir: Option[String],
    typeMatcher: TypeMatcher): List[CompilationUnit]
    
  def compile(
    classStore: ClassStore, 
    namespace: Option[String], 
    schemaOrProtocol: Either[Schema, Protocol],
    outDir: String,
    schemaStore: SchemaStore,
    typeMatcher: TypeMatcher): Unit

  def getName(
    schemaOrProtocol: Either[Schema, Protocol], 
    typeMatcher: TypeMatcher): String
      
  val scalaTreehugger: ScalaTreehugger

  val toolName: String

  val toolShortDescription: String  
  
  ///////////////////////////// concrete members ///////////////////////////////
  def fileExt(
    schemaOrProtocol: Either[Schema, Protocol],
    typeMatcher: TypeMatcher) = {
    val maybeCustomEnumStyle = typeMatcher.customEnumStyleMap.get("enum")
    val enumExt = maybeCustomEnumStyle match {
      case Some("java enum") => ".java"
      case _ => ".scala"
    }
    schemaOrProtocol match {
      case Left(schema) => schema.getType match {
        case RECORD => ".scala"
        case ENUM => enumExt // Avro's SpecificData requires enums be Java Enum
        case _ => sys.error("Only RECORD and ENUM can be top-level definitions")
      }
      case Right(protocol) => ".scala"
    }
  }

  def getFilePath(
    namespace: Option[String],
    schemaOrProtocol: Either[Schema, Protocol],
    maybeOutDir: Option[String],
    typeMatcher: TypeMatcher): Option[Path] = {
    maybeOutDir match {
      case Some(outDir) => {
        val folderPath: Path = Paths.get{
          if (namespace.isDefined) {
            s"$outDir/${namespace.get.toString.replace('.','/')}"
          }
          else outDir
        }
        val ext = fileExt(schemaOrProtocol, typeMatcher)
        val fileName = getName(schemaOrProtocol, typeMatcher) + ext
        if (!Files.exists(folderPath)) Files.createDirectories(folderPath)
        Some(Paths.get(s"$folderPath/$fileName"))
      }
      case None => None
    }

  }
  
  def getLocalSubtypes(protocol: Protocol): List[Schema] = {
    val protocolNS = protocol.getNamespace
    val types = protocol.getTypes.toList
    def isTopLevelNamespace(schema: Schema) = schema.getNamespace == protocolNS
    types.filter(isTopLevelNamespace)
  }
  
  def getJavaEnumCompilationUnit(
    classStore: ClassStore,
    namespace: Option[String],
    schema: Schema,
    maybeOutDir: Option[String],
    typeMatcher: TypeMatcher): CompilationUnit = {
    val maybeFilePath =
      getFilePath(namespace, Left(schema), maybeOutDir, typeMatcher)
    val codeString = JavaTreehugger.asJavaCodeString(
      classStore,
      namespace,
      schema)
    CompilationUnit(maybeFilePath, codeString)
  }
  
  // Uses treehugger trees so can't handle java enums, therefore Java enums
  // must be generated separately, and Scala enums must NOT be generated within 
  // the compilation unit if enum style is set to "java enum".
  def getScalaCompilationUnit(
    classStore: ClassStore,
    namespace: Option[String],
    schemaOrProtocol: Either[Schema, Protocol],
    typeMatcher: TypeMatcher,
    schemaStore: SchemaStore,
    maybeOutDir: Option[String]): CompilationUnit = {
    val scalaFilePath =
      getFilePath(namespace, schemaOrProtocol, maybeOutDir, typeMatcher)
    val scalaString = scalaTreehugger.asScalaCodeString(
      classStore,
      namespace,
      schemaOrProtocol,
      typeMatcher,
      schemaStore)
    CompilationUnit(scalaFilePath, scalaString)
  }
  
  def isEnum(schema: Schema) = schema.getType == Schema.Type.ENUM
  
  def registerTypes(
    schemaOrProtocol: Either[Schema, Protocol],
    classStore: ClassStore,
    typeMatcher: TypeMatcher): Unit = {
    def registerSchema(schema: Schema): Unit = {
      val typeName = typeMatcher.customEnumStyleMap.get("enum") match {
        case Some("java enum") => schema.getName
        case Some("case object") => schema.getName
        case _ => renameEnum(schema, "Value")
      }
      val classSymbol = RootClass.newClass(typeName)
      classStore.accept(schema, classSymbol)
    }
    schemaOrProtocol match {
      case Left(schema) => registerSchema(schema)
      case Right(protocol) => protocol.getTypes.toList.foreach(schema => {
        registerSchema(schema)
      })
    }
  }
  
  def renameEnum(schema: Schema, selector: String) = {
    schema.getType match {
      case RECORD => schema.getName
      case ENUM => schema.getName + "." + selector
      case _ => sys.error("Only RECORD and ENUM can be top-level definitions")
    }
  }
  
  def writeToFile(compilationUnit: CompilationUnit): Unit = {
    val path = compilationUnit.maybeFilePath match {
      case Some(filePath) => filePath
      case None => sys.error("Cannot write to file without a file path")
    }
    val contents = compilationUnit.codeString.getBytes()
    try { // delete old and/or create new
      Files.deleteIfExists(path)
      Files.write(path, contents, StandardOpenOption.CREATE) 
      () 
    }
    catch {
      case ex: FileNotFoundException => sys.error("File not found:" + ex)
      case ex: IOException => sys.error("Problem using the file: " + ex)
    }
  }
  
}
