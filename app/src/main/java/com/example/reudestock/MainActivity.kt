package com.example.reudestock

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
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
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.ui.window.Dialog
import java.io.OutputStream
import kotlinx.coroutines.flow.MutableSharedFlow
import androidx.lifecycle.lifecycleScope
import androidx.compose.ui.text.input.PasswordVisualTransformation
import java.io.File
import com.hierynomus.smbj.SMBClient
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2ShareAccess
import kotlinx.coroutines.CoroutineScope
import java.io.ByteArrayOutputStream

const val STOCK_FILE_NAME = "STOCKDAT.TXT"

data class Product(val code: String, var quantity: Int)

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
    var geo by remember { mutableStateOf("") }
    val updateGeo: (String) -> Unit = { newValue -> geo = newValue }
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

    // Network export dialog state
    var showNetworkExportDialog by remember { mutableStateOf(false) }
    var networkIp by remember { mutableStateOf("192.168.1.100") }
    var networkFolder by remember { mutableStateOf("SANNER") }
    var networkLogin by remember { mutableStateOf("scanner") }
    var networkPassword by remember { mutableStateOf("SCANNER") }

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

    @Composable
    fun ShowSnackbarMessage(message: String, snackbarHostState: SnackbarHostState) {
        LaunchedEffect(Unit) {
            snackbarHostState.showSnackbar(
                message = message
            )
        }
    }

    fun exportDataWithUri(uri: Uri) {
        context.contentResolver.openOutputStream(uri)?.use { outputStream ->
            exportData(products, outputStream)
            lifecycleScope.launch {
                snackbarEvent.emit("Archivo $STOCK_FILE_NAME exportado con éxito")
            }
        } ?: run { // Si openOutputStream devuelve null
            lifecycleScope.launch {
                snackbarEvent.emit("Error al abrir el archivo para exportación")
            }
        }
    }

    fun deleteExistingFile(file: File) {
        if (file.exists() && file.delete()) {
            lifecycleScope.launch {
                snackbarHostState.showSnackbar("Archivo $STOCK_FILE_NAME borrado con éxito")
            }
        }
    }

    fun startExportFlow() {
        val file = File(context.getExternalFilesDir(null), STOCK_FILE_NAME)
        Log.d("elisha", "File path: ${file.absolutePath}")
        val exportUri = Uri.fromFile(file)
        deleteExistingFile(file)
        exportDataWithUri(exportUri)
    }

    val requestPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startExportFlow()
        } else {
            lifecycleScope.launch {
                snackbarHostState.showSnackbar("Permiso denegado")
            }
        }
    }

    fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            // Permissions are already granted, start export flow
            startExportFlow()
        }
    }

    fun onExportNetworkClick(ip: String, folder: String, login: String, password: String) {
        lifecycleScope.launch {
            try {
                val client = SMBClient()
                val connection: Connection = client.connect(ip)
                val authContext = AuthenticationContext(login, password.toCharArray(), null)
                val session: Session = connection.authenticate(authContext)

                // Assuming the share name is the same as the folder name
                val share: DiskShare = session.connectShare(folder) as DiskShare

                val outputStream = ByteArrayOutputStream()
                exportData(products, outputStream)
                val fileContent = outputStream.toByteArray()

                share.openFile(
                    STOCK_FILE_NAME,
                    setOf(AccessMask.GENERIC_WRITE),
                    null,
                    setOf(SMB2ShareAccess.FILE_SHARE_WRITE), // Share Access
                    SMB2CreateDisposition.FILE_OVERWRITE_IF, // Create Disposition
                    null
                ).use { file ->
                    file.write(fileContent, 0)
                }

                share.close()
                session.close()
                connection.close()

                snackbarEvent.emit("Archivo $STOCK_FILE_NAME exportado a la red con éxito")
            } catch (e: Exception) {
                snackbarEvent.emit("Error al exportar a la red: ${e.message}")
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
                    val increasedProducts = products.map { // Asigna el resultado de map a una variable
                        if (it == existingProduct) it.copy(
                            quantity = (it.quantity + quantityInt).coerceAtMost(
                                9999
                            )
                        )
                        else it
                    }
                    increasedProducts // Devuelve la lista con la cantidad aumentada
                }
            } else if (quantityInt > 0) {
                products + Product(code, quantityInt) // Añade el producto
            } else {
                products // No hay cambios
            }
            products = newProducts // Actualiza la variable de estado con la nueva lista
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

    snackbarMessage?.let { message ->ShowSnackbarMessage(message, snackbarHostState)
        LaunchedEffect(Unit) {
            snackbarHostState.showSnackbar(
                message = message
            )
            snackbarMessage = null
        }
    }

    if (showNetworkExportDialog) {
        AlertDialog(
            onDismissRequest = { showNetworkExportDialog = false },
            title = { Text("Exportar a la Red") },
            text = {
                Column {
                    OutlinedTextField(
                        value = networkIp,
                        onValueChange = { networkIp = it },
                        label = { Text("Dirección IP") }
                    )
                    OutlinedTextField(
                        value = networkFolder,
                        onValueChange = { networkFolder = it },
                        label = { Text("Carpeta") }
                    )
                    OutlinedTextField(
                        value = networkLogin,
                        onValueChange = { networkLogin = it },
                        label = { Text("Login") }
                    )
                    OutlinedTextField(
                        value = networkPassword,
                        onValueChange = { networkPassword = it },
                        label = { Text("Contraseña") },
                        visualTransformation = PasswordVisualTransformation()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    onExportNetworkClick(networkIp, networkFolder, networkLogin, networkPassword)
                    showNetworkExportDialog = false
                }) {
                    Text("Exportar")
                }
            },
            dismissButton = {
                Button(onClick = { showNetworkExportDialog = false }) {
                    Text("Cancelar")
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
                            text = { Text("Borrar Lista") },
                            onClick = {
                                showDialog = true
                                showMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Valor Geo") },
                            onClick = {
                                showGeoDialog = true
                                showMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Exportar Datos Local") },
                            onClick = {
                                checkPermissions()
                                showMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Exportar Datos Red") },
                            onClick = {
                                showNetworkExportDialog = true
                                showMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(if (scaneoContinuo) "Desactivar Escaneo Continuo" else "Activar Escaneo Continuo") },
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
                label = { Text("Escanear/Ingresar Código de Barras") },
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
                        label = { Text("Cantidad") },
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
                    Text("Total Productos: ${products.size}", fontWeight = FontWeight.Bold)
                    Text("Total Cantidad: ${products.sumOf { it.quantity }}", fontWeight = FontWeight.Bold)
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
                            Text("Valor Geo")
                            OutlinedTextField(
                                value = geo,
                                onValueChange = updateGeo,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = { showGeoDialog = false }) {
                                Text("Aceptar")
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