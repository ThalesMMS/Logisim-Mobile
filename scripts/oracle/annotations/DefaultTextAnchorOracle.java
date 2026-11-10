import com.cburch.logisim.Main;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.proj.Project;
import com.cburch.draw.shapes.DrawAttr;
import com.cburch.draw.shapes.Text;
import java.awt.image.BufferedImage;
import java.io.File;

public final class DefaultTextAnchorOracle {
  private static void bounds(com.cburch.logisim.data.Bounds value) {
    if (value.getX() != -60 || value.getY() != -40 || value.getWidth() != 61 || value.getHeight() != 80) throw new AssertionError(value);
  }
  public static void main(String[] args) throws Exception {
    Thread.setDefaultUncaughtExceptionHandler((thread, error) -> { error.printStackTrace(); System.exit(1); });
    Main.headless = true;
    var file = new Loader(null).openLogisimFile(new File(args[0]));
    var project = new Project(file);
    try {
      var appearance = file.getCircuit("child").getAppearance();
      bounds(appearance.getOffsetBounds());
      var graphics = new BufferedImage(200, 200, BufferedImage.TYPE_INT_ARGB).createGraphics();
      try {
        for (var object : appearance.getCustomObjectsFromBottom()) {
          if (object instanceof Text text) {
            if (text.getValue(DrawAttr.HALIGNMENT) != DrawAttr.HALIGN_CENTER || text.getValue(DrawAttr.VALIGNMENT) != DrawAttr.VALIGN_MIDDLE) throw new AssertionError("Wrong default text alignment");
          }
          object.paint(graphics, null);
        }
      } finally { graphics.dispose(); }
      bounds(appearance.getOffsetBounds());
      System.out.println("PASS exact Module fixture: default text center/middle; body rect (-60,-40,0,40), desktop inclusive anchor width61 before and after painting");
    } finally { project.getSimulator().shutDown(); }
    System.exit(0);
  }
}
