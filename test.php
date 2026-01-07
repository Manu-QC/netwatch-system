<?php
$archivo = 'devices.json';
echo "<h2>Diagnóstico de Permisos</h2>";
echo "Usuario ejecutando PHP: <strong>" . exec('whoami') . "</strong><br>";

if (is_writable(__DIR__)) {
    echo "✅ La carpeta TIENE permisos de escritura.<br>";
} else {
    echo "❌ La carpeta NO tiene permisos de escritura.<br>";
}

// Intentar crear el archivo
if (file_put_contents($archivo, '[]') !== false) {
    echo "✅ Éxito: Se pudo escribir en devices.json";
} else {
    echo "❌ Error: file_put_contents falló.";
}
?>
