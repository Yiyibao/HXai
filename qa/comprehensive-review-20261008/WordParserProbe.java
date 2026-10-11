import java.nio.file.*;
import me.rerere.rikkahub.feature.material.MaterialWordContent;
class WordParserProbe {
 public static void main(String[] args) throws Exception {
  String text=MaterialWordContent.INSTANCE.text(Files.readString(Path.of(args[0])));
  System.out.println(text);
  System.out.println("original_fraction_assertion="+text.replace(" ", "").contains("(x+1)/(2)"));
  System.out.println("power_assertion="+text.replace(" ", "").contains("(y)^(3)"));
 }
}
