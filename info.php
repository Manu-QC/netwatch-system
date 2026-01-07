<?php
// info.php - Radar Profesional v3
// Mejoras: Calibración Interior + Lectura de Nombres Reales (Bluetooth)

// ================= CONFIGURACIÓN =================
define('API_KEY', 'ESP32_SUPER_SECRETO'); 
define('JSON_FILE', __DIR__ . '/devices.json');
define('CACHE_FILE', __DIR__ . '/cache.json');
define('RSSI_MIN', -95); 

// ================= BASE DE DATOS EXTENDIDA =================
$ouiTable = [
    // --- XIAOMI / REDMI / POCO ---
    "D8:0D:17"=>["Xiaomi","Redmi/Mi Device","CEL"],
    "70:5F:A3"=>["Xiaomi","Redmi/POCO (Detectado)","CEL"],
    "C0:E4:34"=>["Xiaomi","POCO X3/X4","CEL"],
    "AC:67:B2"=>["POCO","M4 Pro/F3","CEL"],
    "50:7B:9D"=>["Realme","Realme Device","CEL"],
    
    // --- SAMSUNG ---
    "88:DE:7C"=>["Samsung","Galaxy Device","CEL"],
    "BC:6E:E2"=>["Samsung","Galaxy Device","CEL"],
    "3C:7A:AA"=>["Samsung","Galaxy Device (New)","CEL"],
    "50:5B:1D"=>["Samsung","Galaxy Device (New)","CEL"],
    "4C:50:DD"=>["Samsung","Galaxy Device (New)","CEL"],
    "70:B1:3D"=>["Samsung","Galaxy Device","CEL"],

    // --- APPLE ---
    "44:3B:14"=>["Apple","iPhone/iPad/Mac","CEL"],
    "CC:20:E8"=>["Apple","iPhone/iPad/Mac","CEL"],
    "F8:E7:B5"=>["Apple","iPhone/iPad/Mac","CEL"],

    // --- IOT / OTROS ---
    "E8:6D:E9"=>["Tuya/SmartLife","IoT Device","OTR"],
    "2C:96:82"=>["Espressif","ESP32/IoT","OTR"],
    "36:6B:44"=>["Microsoft","Windows Device","PC"],
    "08:33:ED"=>["Intel","PC/Laptop","PC"]
];

// ================= FUNCIONES =================
function normalize_mac($mac){
    $m = strtoupper(preg_replace('/[^A-F0-9]/','',$mac));
    if(strlen($m)!==12) return false;
    return implode(':',str_split($m,2));
}

function load_json_file($path) {
    if(!file_exists($path)) return [];
    return json_decode(file_get_contents($path), true) ?: [];
}

function save_json_file($path, $data) {
    file_put_contents($path, json_encode($data, JSON_PRETTY_PRINT|JSON_UNESCAPED_UNICODE), LOCK_EX);
}

// ================= CLASIFICACIÓN =================
function classify_device($mac, $type, $manufData, $ouiTable, &$cache){
    // Prioridad 1: Revisar caché
    if(isset($cache[$mac]) && $cache[$mac]['manufacturer'] !== 'Desconocido' && $cache[$mac]['manufacturer'] !== 'Smartphone Privado') {
        if(!($type === 'BLE' && !empty($manufData))) return $cache[$mac];
    }

    $manufacturer = 'Desconocido';
    $model = 'Dispositivo Genérico';
    $devType = ($type === 'WIFI') ? 'CEL' : 'BLE'; 

    // Lógica BLE (Manuf Data)
    if ($type === 'BLE' && !empty($manufData)) {
        if (strpos($manufData, '4C00') === 0 || strpos($manufData, '004C') !== false) {
            $manufacturer = 'Apple'; $model = 'iBeacon / iPhone'; $devType = 'CEL';
        }
        elseif (strpos($manufData, '0600') === 0) {
            $manufacturer = 'Microsoft'; $model = 'Windows Device'; $devType = 'PC';
        }
    }

    // Lógica OUI (WiFi)
    if ($manufacturer === 'Desconocido') {
        $prefix = substr($mac, 0, 8); 
        if(isset($ouiTable[$prefix])) {
            $manufacturer = $ouiTable[$prefix][0];
            $model = $ouiTable[$prefix][1];
            $devType = $ouiTable[$prefix][2];
        } else {
            // Detección MAC Aleatoria
            $first_byte = hexdec(substr($mac,0,2));
            if ($first_byte & 0x02) {
                $manufacturer = 'Smartphone Privado';
                $model = 'MAC Aleatoria (Probable Xiaomi/Samsung)';
                $devType = 'CEL';
            }
        }
    }

    $info = ['manufacturer'=>$manufacturer, 'model'=>$model, 'type'=>$devType];
    $cache[$mac] = $info;
    save_json_file(CACHE_FILE, $cache);
    return $info;
}

// ================= API BACKEND (POST) =================
if($_SERVER['REQUEST_METHOD'] === 'POST'){
    $input = file_get_contents('php://input');
    $data = json_decode($input, true);
    $current = load_json_file(JSON_FILE);
    $cache = load_json_file(CACHE_FILE);
    $now = time();

    if($data && isset($data['devices'])){
        foreach($data['devices'] as $d){
            $mac = normalize_mac($d['mac']);
            if(!$mac) continue;
            
            $rssi = intval($d['rssi']);
            if($rssi < RSSI_MIN) continue;

            $type = $d['type'] ?? 'WIFI';
            $manuf = $d['manuf'] ?? '';
            // *** CORRECCIÓN PRINCIPAL: LEER EL NOMBRE ***
            $name = $d['name'] ?? ''; 

            // 1. Clasificación estándar
            $info = classify_device($mac, $type, $manuf, $ouiTable, $cache);

            // 2. MEJORA: Si el ESP32 nos manda un nombre, SOBRESCRIBIMOS la clasificación
            if (!empty($name)) {
                $info['model'] = $name; // Ej: "Redmi Note 11S"
                $info['manufacturer'] = "Dispositivo Reconocido";
                
                // Intentar adivinar fabricante por el nombre
                if (stripos($name, 'Redmi') !== false || stripos($name, 'POCO') !== false || stripos($name, 'Mi ') !== false) {
                    $info['manufacturer'] = "Xiaomi";
                }
                elseif (stripos($name, 'Galaxy') !== false || stripos($name, 'Samsung') !== false) {
                    $info['manufacturer'] = "Samsung";
                }
                elseif (stripos($name, 'iPhone') !== false || stripos($name, 'iPad') !== false) {
                    $info['manufacturer'] = "Apple";
                }
                
                // Actualizamos caché para recordar este nombre en el futuro
                $cache[$mac] = $info;
                save_json_file(CACHE_FILE, $cache);
            }

            if(!isset($current[$mac])) {
                $current[$mac] = ['mac'=>$mac, 'first_seen'=>$now, 'samples'=>0];
            }
            
            $entry = &$current[$mac];
            // Promedio RSSI
            $old_rssi = isset($entry['rssi']) ? $entry['rssi'] : $rssi;
            $entry['rssi'] = intval(($old_rssi * 0.4) + ($rssi * 0.6));
            
            $entry['manufacturer'] = $info['manufacturer'];
            $entry['model'] = $info['model'];
            $entry['type'] = $info['type'];
            $entry['last_src'] = $type;
            $entry['last_seen'] = $now;
            $entry['timestamp'] = date('Y-m-d H:i:s');
            $entry['samples']++;
        }
    }
    
    foreach($current as $m => $val){ if(($now - $val['last_seen']) > 60) unset($current[$m]); }
    
    save_json_file(JSON_FILE, $current);
    echo json_encode(['status'=>'ok']);
    exit;
}

// ================= API FRONTEND (GET) =================
if(isset($_GET['json'])){
    header('Content-Type: application/json');
    header("Cache-Control: no-cache");
    $data = load_json_file(JSON_FILE);
    echo json_encode(array_values($data));
    exit;
}
?>

<!DOCTYPE html>
<html lang="es">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Radar Calibrado</title>
    <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.0.0/css/all.min.css">
    <style>
        body { font-family: 'Segoe UI', sans-serif; background: #eef2f5; margin: 0; padding: 10px; }
        .card { background: white; border-radius: 12px; box-shadow: 0 4px 15px rgba(0,0,0,0.05); padding: 15px; margin-bottom: 15px; }
        
        .stats { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; text-align: center; }
        .stat-item h2 { margin: 0; font-size: 1.8rem; color: #333; }
        .stat-item span { font-size: 0.8rem; color: #777; text-transform: uppercase; }

        table { width: 100%; border-collapse: collapse; margin-top: 10px; }
        th { text-align: left; color: #888; font-size: 0.8rem; padding: 10px; border-bottom: 2px solid #eee; }
        td { padding: 12px 8px; border-bottom: 1px solid #f0f0f0; }
        
        .dev-title { font-weight: bold; color: #2c3e50; font-size: 1rem; }
        .dev-mac { font-family: monospace; font-size: 0.8rem; color: #999; }
        .dist-tag { font-weight: bold; padding: 4px 8px; border-radius: 6px; font-size: 0.9rem; display: inline-block; min-width: 60px; text-align: center; }
        .dist-near { background: #d4edda; color: #155724; }
        .dist-med { background: #fff3cd; color: #856404; }
        .dist-far { background: #f8d7da; color: #721c24; }
        
        .badge { font-size: 0.7rem; padding: 2px 6px; border-radius: 4px; color: white; background: #999; }
        .bg-cel { background: #28a745; } .bg-pc { background: #17a2b8; }
    </style>
</head>
<body>

<div class="card">
    <div class="stats">
        <div class="stat-item">
            <h2 id="s-total">0</h2><span>Total</span>
        </div>
        <div class="stat-item">
            <h2 id="s-cel">0</h2><span>Móviles</span>
        </div>
        <div class="stat-item">
            <h2 id="s-pc">0</h2><span>PCs</span>
        </div>
    </div>
</div>

<div class="card">
    <table>
        <thead><tr><th>DISPOSITIVO</th><th>DISTANCIA</th><th>ULT. VEZ</th></tr></thead>
        <tbody id="list"><tr><td colspan="3" align="center">Escaneando...</td></tr></tbody>
    </table>
</div>

<script>
    // === CALIBRACIÓN DE DISTANCIA ===
    // RSSI a 1 metro: -55 (Ajustable)
    // Factor N (Obstáculos): 3.0 (Interior)
    function calculateDistance(rssi) {
        let txPower = -55; 
        let n = 3.0; 
        let d = Math.pow(10, (txPower - rssi) / (10 * n));
        return d;
    }

    async function update() {
        try {
            let res = await fetch('?json=1');
            let data = await res.json();
            
            // Ordenar por distancia (menor a mayor)
            data.sort((a,b) => b.rssi - a.rssi);

            let html = '';
            let cCel=0, cPc=0;

            data.forEach(d => {
                if(d.type==='CEL') cCel++;
                if(d.type==='PC') cPc++;

                let distM = calculateDistance(d.rssi);
                let distText = distM < 1 ? (distM*100).toFixed(0)+" cm" : distM.toFixed(1)+" m";
                
                // Color según cercanía
                let distClass = 'dist-far';
                if(distM < 1.5) distClass = 'dist-near'; // Menos de 1.5m es verde
                else if(distM < 5) distClass = 'dist-med'; // Menos de 5m es amarillo

                let icon = d.last_src === 'BLE' ? '🔵' : '🟢';
                let typeBadge = d.type === 'CEL' ? 'bg-cel' : (d.type==='PC'?'bg-pc':'');
                
                html += `<tr>
                    <td>
                        <div class="dev-title">${d.manufacturer}</div>
                        <div style="font-size:0.8rem; color:#666">${d.model}</div>
                        <div class="dev-mac">${d.mac} <span class="badge ${typeBadge}">${d.type}</span></div>
                    </td>
                    <td>
                        <div class="dist-tag ${distClass}">${distText}</div>
                        <div style="font-size:0.75rem; color:#aaa; text-align:center; margin-top:2px">${d.rssi} dBm</div>
                    </td>
                    <td style="font-size:0.8rem; color:#777">
                        ${icon} hace ${Math.floor(Date.now()/1000 - d.last_seen)}s
                    </td>
                </tr>`;
            });

            document.getElementById('list').innerHTML = html || '<tr><td colspan="3" align="center">Sin señal...</td></tr>';
            document.getElementById('s-total').innerText = data.length;
            document.getElementById('s-cel').innerText = cCel;
            document.getElementById('s-pc').innerText = cPc;

        } catch(e) {}
    }
    setInterval(update, 2000);
    update();
</script>
</body>
</html>
