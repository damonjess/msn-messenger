import java.io.File
fun main() {
    val f = File("test")
    val isInlineOnly = f.length() in 1..1000
    println(isInlineOnly)
}
