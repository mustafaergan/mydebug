/**
 * MyDebug deneme uygulaması. Debug ile başlatın, aşağıdaki metni değiştirip kaydedin (Ctrl+S):
 * konsoldaki çıktı yeniden başlatmadan değişmeli. JBR ile çalışıyorsa yeni bir metot ekleyip
 * tick() içinden çağırmayı da deneyin (yapısal değişiklik).
 */
public class Demo {
    public static void main(String[] args) throws InterruptedException {
        Demo demo = new Demo();
        while (true) {
            demo.tick();
            Thread.sleep(1000);
        }
    }

    void tick() {
        System.out.println("Merhaba MyDebug — bu satırı değiştirip kaydedin");
    }
}
