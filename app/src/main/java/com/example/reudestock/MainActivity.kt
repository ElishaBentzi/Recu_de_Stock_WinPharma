package com.example.reudestock

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.ui.window.Dialog
import java.io.OutputStream
import kotlinx.coroutines.flow.MutableSharedFlow
import androidx.lifecycle.lifecycleScope
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.hierynomus.smbj.SMBClient
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2ShareAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

const val STOCK_FILE_NAME = "STOCKDAT.TXT"

data class Product(val code: String, var quantity: Int)
data class NetworkSettings(
    val networkIp: String = "192.168.0.49",
    val networkFolder: String = "\\\\Nuc-intel\\scanner",
    val networkLogin: String = "scanner",
    val networkPassword: String = "SCANNER",
    val geo: String = "1"
)

class DataStoreManager(private val context: Context) {
    companion object {
        private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "network_settings")
        val NETWORK_IP_KEY = stringPreferencesKey("network_ip")
        val NETWORK_FOLDER_KEY = stringPreferencesKey("network_folder")
        val NETWORK_LOGIN_KEY = stringPreferencesKey("network_login")
        val NETWORK_PASSWORD_KEY = stringPreferencesKey("network_password")
        val GEO_KEY = stringPreferencesKey("geo")
    }
    val networkSettingsFlow: Flow<NetworkSettings> = context.dataStore.data.map { preferences ->
        NetworkSettings(
            networkIp = preferences[NETWORK_IP_KEY] ?: "192.168.0.49",
            networkFolder = preferences[NETWORK_FOLDER_KEY] ?: "\\\\Nuc-intel\\scanner",
            networkLogin = preferences[NETWORK_LOGIN_KEY] ?: "scanner",
            networkPassword = preferences[NETWORK_PASSWORD_KEY] ?: "SCANNER",
            geo = preferences[GEO_KEY] ?: "1"
        )
    }
    suspend fun updateNetworkSettings(settings: NetworkSettings) {
        context.dataStore.edit { preferences ->
            preferences[NETWORK_IP_KEY] = settings.networkIp
            preferences[NETWORK_FOLDER_KEY] = settings.networkFolder
            preferences[NETWORK_LOGIN_KEY] = settings.networkLogin
            preferences[NETWORK_PASSWORD_KEY] = settings.networkPassword
            preferences[GEO_KEY] = settings.geo
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppContent(context = this, lifecycleScope = lifecycleScope)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppContent(
    context: Context,
    lifecycleScope: CoroutineScope
) {
    var products by remember { mutableStateOf<List<Product>>(emptyList()) }
    val focusManager = LocalFocusManager.current
    var barcode by remember { mutableStateOf("") }
    var quantity by remember { mutableStateOf(TextFieldValue("1")) }
    var showDialog by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showGeoDialog by remember { mutableStateOf(false) }
    var scaneoContinuo by remember { mutableStateOf(true) }
    val barcodeFocusRequester = remember { FocusRequester() }
    val quantityFocusRequester = remember { FocusRequester() }
    val colors = listOf(Color(0xFFB2EBF2), Color(0xFFFCE4EC), Color(0xFFE8F5E9))
    val coroutineScope = rememberCoroutineScope()
    var modificarCantidad by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    var snackbarMessage by remember { mutableStateOf<String?>(null) }
    val snackbarEvent = MutableSharedFlow<String>()

    var showNetworkExportDialog by remember { mutableStateOf(false) }

    var dataStoreUpdateTrigger by remember { mutableIntStateOf(0) }
    val dataStoreManager = remember { DataStoreManager(context) }
    val networkSettings by dataStoreManager.networkSettingsFlow.collectAsState(initial = NetworkSettings())

    var networkIp by remember { mutableStateOf(networkSettings.networkIp) }
    var networkFolder by remember { mutableStateOf(networkSettings.networkFolder) }
    var networkLogin by remember { mutableStateOf(networkSettings.networkLogin) }
    var networkPassword by remember { mutableStateOf(networkSettings.networkPassword) }
    var geo by remember { mutableStateOf(networkSettings.geo) }

    fun exportData(products: List<Product>, outputStream: OutputStream){
        val formattedGeo = geo.padEnd(9)
        products.forEach { product ->
            val formattedCode = product.code.padEnd(127)
            val formattedQuantity = product.quantity.toString().padStart(4, '0')
            outputStream.write(
                "$formattedGeo;$formattedCode;$formattedQuantity; \r\n".toByteArray()
            )
        }
    }

    fun exportDataWithUri(uri: Uri) {
        try {
            context.contentResolver.openOutputStream(uri, "wt")?.use { outputStream -> // "wt" para truncar y escribir
                exportData(products, outputStream)
                lifecycleScope.launch {
                    snackbarEvent.emit("Fichier $STOCK_FILE_NAME exporté avec succès")
                }
            } ?: run {
                lifecycleScope.launch {
                    snackbarEvent.emit("Erreur lors de l'ouverture du fichier pour l'exportation")
                }
            }
        } catch (e: Exception) {
            lifecycleScope.launch {
                snackbarEvent.emit("Erreur lors de l'exportation du fichier: ${e.message}")
            }
        }
    }

    val createDocumentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                exportDataWithUri(uri)
            }
        }
    }

    fun startExportLocalFlow() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, STOCK_FILE_NAME)
            // Agrega el flag para sobreescribir el archivo si existe
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        createDocumentLauncher.launch(intent)
    }

    fun isValidIp(ip: String): Boolean {
        return ip.matches(Regex("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}"))
    }

    fun startExportNetworkFlow(ip: String, folder: String, login: String, password: String) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { // <-- Ejecuta en un hilo secundario
                try {
                    if (!isValidIp(ip)) {
                        withContext(Dispatchers.Main) {
                            snackbarEvent.emit("Adresse IP invalide")
                        }
                        return@withContext // <-- Retorna del bloque withContext
                    }
                    val client = SMBClient()
                    val serverName = folder.substringAfter("\\\\").substringBefore("\\")
                    val connection: Connection = client.connect(ip) // Usamos IP podemos usar serverName
                    try {
                        val authContext = AuthenticationContext(login, password.toCharArray(), null)
                        val session: Session = connection.authenticate(authContext)
                        try {
                            val shareName = folder.substringAfterLast("\\")
                            val share: DiskShare = session.connectShare(shareName) as DiskShare
                            try {
                                val outputStream = ByteArrayOutputStream()
                                exportData(products, outputStream)
                                val fileContent = outputStream.toByteArray()
                                share.openFile(
                                    STOCK_FILE_NAME,
                                    setOf(AccessMask.GENERIC_WRITE),
                                    null,setOf(SMB2ShareAccess.FILE_SHARE_WRITE),
                                    SMB2CreateDisposition.FILE_OVERWRITE_IF,
                                    null
                                ).use { file ->
                                    file.write(fileContent, 0)
                                }
                                // Usa withContext para emitir el evento en el hilo principal
                                withContext(Dispatchers.Main) {
                                    snackbarEvent.emit("Fichier $STOCK_FILE_NAME exporté vers le réseau avec succès")
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    snackbarEvent.emit("Erreur d'écriture du fichier à partager: ${e.message}")
                                }
                            } finally {
                                share.close()
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                snackbarEvent.emit("Erreur de connexion au partage: ${e.message}")
                            }
                        } finally {
                            session.close()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            snackbarEvent.emit("Erreur d'authentification: ${e.message}")
                        }
                    } finally {
                        connection.close()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        snackbarEvent.emit("Erreur de connexion au serveur SMB: ${e.message}")
                    }
                }
            }
        }
    }

    fun startSingleUseTimer() {
        coroutineScope.launch {
            delay(500)
            if (scaneoContinuo) {
                barcodeFocusRequester.requestFocus()
            } else {
                quantityFocusRequester.requestFocus()
                quantity = TextFieldValue(
                    text = quantity.text,
                    selection = TextRange(0, quantity.text.length)
                )
            }
        }
    }

    fun agregarProducto() {
        if (barcode.isNotBlank() && quantity.text.isNotBlank()) {
            val code = barcode.filter { it.isDigit() }
            val existingProduct = products.find { it.code == code }
            val quantityInt = quantity.text.toIntOrNull() ?: 0
            val newProducts = if (existingProduct != null) {
                if (quantityInt == 0) {
                    products.filter { it != existingProduct }
                } else if (modificarCantidad) {
                    val updatedProducts = products.map {
                        if (it== existingProduct) it.copy(quantity = quantityInt)
                        else it
                    }
                    modificarCantidad = false
                    updatedProducts
                } else {
                    val increasedProducts = products.map {
                        if (it == existingProduct) it.copy(
                            quantity = (it.quantity + quantityInt).coerceAtMost(
                                9999
                            )
                        )
                        else it
                    }
                    increasedProducts
                }
            } else if (quantityInt > 0) {
                products + Product(code, quantityInt)
            } else {
                products
            }
            products = newProducts
            quantity = TextFieldValue("1")
            barcode = ""
            barcodeFocusRequester.requestFocus()
        }
    }

    LaunchedEffect(Unit) {
        barcodeFocusRequester.requestFocus()
    }

    LaunchedEffect(key1 = snackbarEvent) {
        snackbarEvent.collect { message ->
            snackbarHostState.showSnackbar(message = message)
        }
    }

    LaunchedEffect(networkIp, networkFolder, networkLogin, networkPassword, geo) {
        dataStoreManager.updateNetworkSettings(
            NetworkSettings(
                networkIp = networkIp,
                networkFolder = networkFolder,
                networkLogin = networkLogin,
                networkPassword = networkPassword,
                geo = geo
            )
        )
        dataStoreUpdateTrigger++
    }

    snackbarMessage?.let { message ->
        LaunchedEffect(Unit) {
            snackbarHostState.showSnackbar(message = message)
            snackbarMessage = null
        }
    }

    if (showNetworkExportDialog) {
        AlertDialog(
            onDismissRequest = { showNetworkExportDialog = false },
            title = { Text("Exporter vers le Réseau") },
            text = {
                Column {
                    OutlinedTextField(
                        value = networkIp,
                        onValueChange = {  newValue ->
                            networkIp = newValue
                        },
                        label = { Text("Adresse IP") }
                    )
                    OutlinedTextField(
                        value = networkFolder,
                        onValueChange = {  newValue ->
                            networkFolder = newValue
                        },
                        label = { Text("Dossier") }
                    )
                    OutlinedTextField(
                        value = networkLogin,
                        onValueChange = {  newValue ->
                            networkLogin = newValue
                        },
                        label = { Text("Login") }
                    )
                    OutlinedTextField(
                        value = networkPassword,
                        onValueChange = {  newValue ->
                            networkPassword = newValue
                        },
                        label = { Text("Password") },
                        visualTransformation = PasswordVisualTransformation()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    startExportNetworkFlow(networkIp, networkFolder, networkLogin, networkPassword)
                    showNetworkExportDialog = false
                }) {
                    Text("Exporter")
                }
            },
            dismissButton = {
                Button(onClick = { showNetworkExportDialog = false }) {
                    Text("Annuler")
                }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Reçu de Stock") },
                actions = {
                    IconButton(onClick = { showMenu = !showMenu }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Menú")
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Supprimer la liste") },
                            onClick = {
                                showDialog = true
                                showMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Valeur Geo") },
                            onClick = {
                                showGeoDialog = true
                                showMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Données Réseau") },
                            onClick = {
                                showNetworkExportDialog = true
                                showMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Exporter des Données Locales") },
                            onClick = {
                                startExportLocalFlow()
                                showMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Exporter les Données du Réseau") },
                            onClick = {
                                startExportNetworkFlow(networkIp, networkFolder, networkLogin, networkPassword)
                                showMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(if (scaneoContinuo) "Désactiver Scanner Continue" else "Activer Scanner Continue") },
                            onClick = {
                                scaneoContinuo = !scaneoContinuo
                                showMenu = false
                            }
                        )
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .clickable { focusManager.clearFocus() }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp)
            ) {OutlinedTextField(
                value = barcode,
                onValueChange = { newValue ->
                    if (newValue.endsWith('\t') || newValue.endsWith('\n') || newValue.endsWith('\r')) {
                        startSingleUseTimer()
                        barcode = newValue.filter { it.isDigit() }
                        if (barcode.length > 16) {barcode = barcode.drop(3).take(13)
                        }
                        if (scaneoContinuo) {
                            agregarProducto()
                        } else {
                            quantityFocusRequester.requestFocus()
                            quantity = TextFieldValue(
                                text = quantity.text,
                                selection = TextRange(0, quantity.text.length)
                            )
                        }
                    } else {
                        barcode = newValue
                    }
                },
                label = { Text("Scanner/Saisir le code-barres") },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(barcodeFocusRequester)
                    .focusProperties { this.next = quantityFocusRequester }
                    .onFocusChanged {
                        barcode = barcode.filter { it.isDigit() }
                        if (barcode.length > 16) {
                            barcode = barcode
                                .drop(3)
                                .take(13)
                        }
                    },
                textStyle = LocalTextStyle.current.copy(fontSize = 22.sp),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Next
                ),
                keyboardActions = KeyboardActions(onNext = {
                    quantityFocusRequester.requestFocus()
                    quantity = TextFieldValue(
                        text = quantity.text,
                        selection = TextRange(0, quantity.text.length)
                    )
                })
            )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value= quantity,
                        onValueChange = { newValue ->
                            val filteredText = newValue.text.filter { it.isDigit() }
                            val newQuantity = (filteredText.toIntOrNull() ?: 0).coerceAtMost(9999)
                            quantity = newValue.copy(text = newQuantity.toString())
                        },
                        label = { Text("Quantité") },
                        modifier = Modifier
                            .width(100.dp)
                            .focusRequester(quantityFocusRequester)
                            .focusProperties { this.previous = barcodeFocusRequester }
                            ,
                        textStyle = LocalTextStyle.current.copy(fontSize =22.sp),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                agregarProducto()
                            }
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))

                    IconButton(onClick = {
                        val newQuantity = ((quantity.text.toIntOrNull() ?: 0) + 1).coerceAtMost(9999)
                        quantity = TextFieldValue(newQuantity.toString())
                    }) {
                        Icon(Icons.Filled.Add, contentDescription = "Aumentar Cantidad")
                    }
                    IconButton(onClick = {
                        val newQuantity = (quantity.text.toIntOrNull() ?: 0) - 1
                        quantity = if (newQuantity >= 0) TextFieldValue(newQuantity.toString()) else TextFieldValue("0")
                    }) {
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = "Disminuir Cantidad")
                    }
                    IconButton(onClick = {
                        agregarProducto()
                    }){
                        Icon(Icons.Filled.Check, contentDescription = "Agregar Producto", tint = Color.Blue)
                    }
                    IconButton(onClick = {
                        modificarCantidad = false
                        barcode = ""
                        barcodeFocusRequester.requestFocus()
                        quantity = TextFieldValue("1")
                    }) {
                        Icon(Icons.Filled.Clear, contentDescription = "Cancelar", tint = Color.Red)
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                LazyColumn(
                    modifier = Modifier.weight(1f)
                ) {
                    itemsIndexed(products.reversed()) { index, product ->
                        val color = colors[index % colors.size]
                        Column(
                            modifier = Modifier
                                .padding(horizontal = 0.dp, vertical = 0.dp)
                                .background(color)
                        ) {
                            ProductItem(
                                product = product,
                                onEditClick = { productToEdit ->
                                    modificarCantidad = true
                                    barcode = productToEdit.code
                                    quantity = TextFieldValue(
                                        text = productToEdit.quantity.toString(),
                                        selection = TextRange(0, productToEdit.quantity.toString().length)
                                    )
                                    quantityFocusRequester.requestFocus()
                                }, onDeleteClick = { productToDelete ->
                                    val newProducts = products.filter { it != productToDelete } // Usa productToDelete en el filtro
                                    products = newProducts
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Totales
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Total Productos: ", fontWeight = FontWeight.Bold)
                    Text("${products.size}", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text("Total Cantidad: ", fontWeight = FontWeight.Bold)
                    Text("${products.sumOf { it.quantity }}", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                }
            }

            // Diálogo de confirmación para borrar lista
            if (showDialog) {
                AlertDialog(
                    onDismissRequest = { showDialog = false },
                    title = { Text("Confirmar Borrado") },
                    text = { Text("¿Estás seguro de que quieres borrar la lista de productos?") },
                    confirmButton = {
                        Button(onClick = {
                            products = emptyList()
                            showDialog = false
                        }) {
                            Text("Borrar")
                        }
                    },
                    dismissButton = {
                        Button(onClick = { showDialog = false }) {
                            Icon(imageVector = Icons.Filled.Clear, contentDescription = null)
                            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                            Text("Cancelar")
                        }
                    })
            }

            // Diálogo para editar Valor Geo
            if (showGeoDialog) {
                Dialog(onDismissRequest = { showGeoDialog = false }) {
                    Card {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Valeur Geo")
                            OutlinedTextField(
                                value = geo,
                                onValueChange = { newValue ->
                                    geo = newValue.filter { it.isDigit() }
                                    lifecycleScope.launch {
                                        dataStoreManager.updateNetworkSettings(
                                            networkSettings.copy(geo = geo)
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number) // Abrir teclado numérico
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = { showGeoDialog = false }) {
                                Text("Accepter")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ProductItem(product: Product, onEditClick: (Product) -> Unit, onDeleteClick: (Product) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = 2.dp)
            .height(18.dp)
            .clickable { onEditClick(product) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = product.code,
            modifier = Modifier.weight(1f),
            fontSize = 18.sp,
            color = Color.Black
        )
        Text(
            text = "x${product.quantity}",
            color = Color.Blue,
            fontSize = 20.sp
        )
        IconButton(onClick = { onEditClick(product) }) {
            Icon(
                Icons.Filled.Edit,
                contentDescription = "Editar",
                modifier = Modifier.size(18.dp),
                tint = Color.Magenta
            )
        }
        IconButton(onClick = { onDeleteClick(product) }) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = "Eliminar",
                modifier = Modifier.size(18.dp),
                tint = Color.Red
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    MaterialTheme {
        val context = LocalContext.current
        val coroutineScope = rememberCoroutineScope()
        AppContent(
            context = context,
            lifecycleScope = coroutineScope,
        )
    }
}