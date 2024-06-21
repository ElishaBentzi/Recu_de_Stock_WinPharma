package com.example.reudestock

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.content.ContentUris
import androidx.activity.ComponentActivity
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
import kotlinx.coroutines.flow.SharedFlow
import androidx.lifecycle.lifecycleScope

data class Product(val code: String, var quantity: Int)

fun exportData(products: List<Product>, geo: String, outputStream: OutputStream){
    val formattedGeo = geo.padEnd(9)
    products.forEach { product ->
        val formattedCode = product.code.padEnd(127)
        val formattedQuantity = product.quantity.toString().padStart(4, '0')
        outputStream.write(
            "$formattedGeo;$formattedCode;$formattedQuantity; \r\n".toByteArray()
        )
    }
}

class MainActivity : ComponentActivity() {
    private val requestWritePermission = 100
    private var exportUri: Uri? = null
    private var products by mutableStateOf<List<Product>>(emptyList())
    private var geo by mutableStateOf("")
    private val snackbarEvent = MutableSharedFlow<String>()

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
        if (isGranted) {
            exportDataWithUri()
        } else {
            // Emitir evento para mostrar Snackbar
            lifecycleScope.launch {
                snackbarEvent.emit("Permission denied")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppContent(
                productsInitial = products,
                geo = geo,
                updateGeo = { geo = it },
                onExportLocalClick = { exportDataWithUri() },
                onExportNetworkClick = { /* TODO: Implement network export */ },
                snackbarEvent = snackbarEvent
            )
        }
        checkPermissions()
    }

    private fun startExportFlow() {
        val filename = "STOCKDAT.TXT"
        deleteExistingFileIfExists(filename)

        val createIntent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, filename)
        }
        startActivityForResult(createIntent, 1)
    }

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            startExportFlow()
        }
    }

    @Deprecated("Deprecated in Java") override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == requestWritePermission) {
            if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                startExportFlow()
            } else {
                lifecycleScope.launch {
                    snackbarEvent.emit("Permiso de escritura negado")
                }
            }
        }
    }

    private fun exportDataWithUri() {
        exportUri?.let { uri ->
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                contentResolver.openOutputStream(uri)?.use { outputStream ->
                    exportData(products, geo, outputStream)
                    lifecycleScope.launch {
                        snackbarEvent.emit("Archivo STOCKDAT.TXT exportado con éxito")
                    }
                }
            } else {
                requestPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }

    private fun deleteExistingFileIfExists(filename: String) {
        val uri = findFileUri(filename)
        if (uri != null) {
            contentResolver.delete(uri, null, null)
        }
    }

    private fun findFileUri(filename: String): Uri? {
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME
        )
        val selection = "${MediaStore.Files.FileColumns.DISPLAY_NAME} = ?"
        val selectionArgs = arrayOf(filename)

        val cursor = contentResolver.query(
            MediaStore.Files.getContentUri("external"),
            projection,
            selection,
            selectionArgs,
            null
        )

        cursor?.use {
            if (it.moveToFirst()) {
                val id = it.getLong(it.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID))
                return ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), id)
            }
        }
        return null
    }
}

@Composable
fun ShowSnackbarMessage(message: String, snackbarHostState: SnackbarHostState) {
    // Lanzar Snackbar
    LaunchedEffect(Unit) {
        snackbarHostState.showSnackbar(
            message = message
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppContent(
    productsInitial: List<Product>,
    geo: String,
    updateGeo: (String) -> Unit,
    onExportLocalClick: () -> Unit,
    onExportNetworkClick: () -> Unit,
    snackbarEvent: SharedFlow<String>
) {
    var products by remember { mutableStateOf(productsInitial) }
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

    // Funciones movidas a AppContent
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

    LaunchedEffect(Unit) {
        snackbarEvent.collect { message ->
            snackbarMessage = message
        }
    }

    snackbarMessage?.let { message ->
        ShowSnackbarMessage(message, snackbarHostState) // Llama a ShowSnackbarMessage dentro del contexto @Composable
        // Resetear el mensaje del Snackbar después de mostrarlo
        LaunchedEffect(Unit) {
            snackbarHostState.showSnackbar(
                message = message
            )
            snackbarMessage = null
        }
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
                                onExportLocalClick()
                                showMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Exportar Datos Red") },
                            onClick = {
                                onExportNetworkClick()
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
        val products = remember { mutableStateListOf<Product>() }
        val emptySnackbarEvent = MutableSharedFlow<String>()
        AppContent(
            products,
            "1",
            {},
            {},
            {},
            emptySnackbarEvent
        )
    }
}