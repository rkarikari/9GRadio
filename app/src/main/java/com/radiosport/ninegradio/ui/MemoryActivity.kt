package com.radiosport.ninegradio.ui

import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.*
import androidx.recyclerview.widget.ListAdapter
import com.google.gson.Gson
import com.radiosport.ninegradio.R
import com.radiosport.ninegradio.data.MemoryChannel
import com.radiosport.ninegradio.dsp.DemodMode
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

class MemoryActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()
    private lateinit var adapter: MemoryChannelAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_memory)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "Memory Channels"

        val recyclerView = findViewById<RecyclerView>(R.id.recyclerMemory)
        val fabAdd = findViewById<View>(R.id.fabAddMemory)

        adapter = MemoryChannelAdapter(
            onLoad = { ch ->
                viewModel.loadMemoryChannel(ch)
                Toast.makeText(this, "Tuned to ${ch.name}", Toast.LENGTH_SHORT).show()
                finish()
            },
            onDelete = { ch ->
                AlertDialog.Builder(this)
                    .setTitle("Delete Channel")
                    .setMessage("Delete \"${ch.name}\"?")
                    .setPositiveButton("Delete") { _, _ -> viewModel.deleteMemoryChannel(ch) }
                    .setNegativeButton("Cancel", null)
                    .show()
            },
            onEdit = { ch -> showEditDialog(ch) }
        )

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
        ItemTouchHelper(SwipeToDeleteCallback { ch ->
            viewModel.deleteMemoryChannel(ch)
            Toast.makeText(this, "\"${ch.name}\" deleted", Toast.LENGTH_SHORT).show()
        }).attachToRecyclerView(recyclerView)

        lifecycleScope.launch {
            viewModel.memoryChannels.collectLatest { channels ->
                adapter.submitList(channels)
            }
        }

        fabAdd.setOnClickListener { showAddDialog() }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Export JSON")
        menu.add(0, 2, 0, "Import JSON")
        menu.add(0, 3, 0, "Clear All")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> { finish(); return true }
            1 -> exportChannels()
            2 -> Toast.makeText(this, "Use Files app to select a JSON file", Toast.LENGTH_SHORT).show()
            3 -> confirmClearAll()
        }
        return super.onOptionsItemSelected(item)
    }

    private fun showAddDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_memory_channel, null)
        val etName    = view.findViewById<EditText>(R.id.etChannelName)
        val etFreq    = view.findViewById<EditText>(R.id.etChannelFreq)
        val etGroup   = view.findViewById<EditText>(R.id.etChannelGroup)
        val spinMode  = view.findViewById<Spinner>(R.id.spinnerChannelMode)
        val etNotes   = view.findViewById<EditText>(R.id.etChannelNotes)

        // Pre-fill from current receiver state
        etFreq.setText("%.6f".format(viewModel.centerFreqHz.value / 1e6))
        etGroup.setText("Default")

        val modes = DemodMode.values().map { it.displayName }
        spinMode.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, modes)
        spinMode.setSelection(viewModel.demodMode.value.ordinal)

        AlertDialog.Builder(this)
            .setTitle("Add Memory Channel")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                val name = etName.text.toString().ifBlank { "Channel" }
                val freqMhz = etFreq.text.toString().toDoubleOrNull() ?: return@setPositiveButton
                val freqHz = (freqMhz * 1_000_000).toLong()
                val mode = DemodMode.values().getOrElse(spinMode.selectedItemPosition) { DemodMode.NFM }
                val group = etGroup.text.toString().ifBlank { "Default" }
                val notes = etNotes?.text?.toString() ?: ""
                lifecycleScope.launch {
                    (application as com.radiosport.ninegradio.RtlSdrApplication).database
                        .memoryChannelDao().insert(
                            MemoryChannel(
                                name = name,
                                frequencyHz = freqHz,
                                demodMode = mode.name,
                                sampleRate = viewModel.sampleRate.value,
                                gain = viewModel.gainIndex.value,
                                squelch = viewModel.squelch.value,
                                biasTee = viewModel.biasTee.value,
                                directSampling = viewModel.directSampling.value,
                                ppmCorrection = viewModel.ppm.value,
                                group = group,
                                notes = notes
                            )
                        )
                }
                Toast.makeText(this, "Saved \"$name\"", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEditDialog(channel: MemoryChannel) {
        val view = layoutInflater.inflate(R.layout.dialog_memory_channel, null)
        val etName   = view.findViewById<EditText>(R.id.etChannelName)
        val etFreq   = view.findViewById<EditText>(R.id.etChannelFreq)
        val etGroup  = view.findViewById<EditText>(R.id.etChannelGroup)
        val spinMode = view.findViewById<Spinner>(R.id.spinnerChannelMode)
        val etNotes  = view.findViewById<EditText>(R.id.etChannelNotes)

        etName.setText(channel.name)
        etFreq.setText("%.6f".format(channel.frequencyHz / 1e6))
        etGroup.setText(channel.group)
        etNotes?.setText(channel.notes)

        val modes = DemodMode.values().map { it.displayName }
        spinMode.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, modes)
        val modeIdx = DemodMode.values().indexOfFirst { it.name == channel.demodMode }
        if (modeIdx >= 0) spinMode.setSelection(modeIdx)

        AlertDialog.Builder(this)
            .setTitle("Edit: ${channel.name}")
            .setView(view)
            .setPositiveButton("Update") { _, _ ->
                val name = etName.text.toString().ifBlank { channel.name }
                val freqMhz = etFreq.text.toString().toDoubleOrNull()
                    ?: (channel.frequencyHz / 1e6)
                val mode = DemodMode.values().getOrElse(spinMode.selectedItemPosition) {
                    DemodMode.valueOf(channel.demodMode)
                }
                val group = etGroup.text.toString().ifBlank { channel.group }
                val notes = etNotes?.text?.toString() ?: channel.notes
                val updated = channel.copy(
                    name = name,
                    frequencyHz = (freqMhz * 1_000_000).toLong(),
                    demodMode = mode.name,
                    group = group,
                    notes = notes
                )
                lifecycleScope.launch {
                    (application as com.radiosport.ninegradio.RtlSdrApplication).database
                        .memoryChannelDao().update(updated)
                }
                Toast.makeText(this, "Updated \"$name\"", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmClearAll() {
        AlertDialog.Builder(this)
            .setTitle("Clear All Channels")
            .setMessage("Delete all memory channels? This cannot be undone.")
            .setPositiveButton("Delete All") { _, _ ->
                lifecycleScope.launch {
                    val db = (application as com.radiosport.ninegradio.RtlSdrApplication).database
                    val all = viewModel.memoryChannels.first()
                    all.forEach { db.memoryChannelDao().delete(it) }
                    Toast.makeText(this@MemoryActivity,
                        "Cleared ${all.size} channels", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun exportChannels() {
        lifecycleScope.launch {
            val channels = viewModel.memoryChannels.first()
            val json = Gson().toJson(channels)
            val file = File(getExternalFilesDir(null), "memory_channels_export.json")
            file.parentFile?.mkdirs()
            file.writeText(json)
            Toast.makeText(this@MemoryActivity,
                "Exported ${channels.size} channels to ${file.name}", Toast.LENGTH_LONG).show()
        }
    }
}

class MemoryChannelAdapter(
    private val onLoad: (MemoryChannel) -> Unit,
    private val onDelete: (MemoryChannel) -> Unit,
    private val onEdit: (MemoryChannel) -> Unit
) : ListAdapter<MemoryChannel, MemoryChannelAdapter.ViewHolder>(DiffCallback()) {

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvChannelName)
        val tvFreq: TextView = view.findViewById(R.id.tvChannelFreq)
        val tvMode: TextView = view.findViewById(R.id.tvChannelMode)
        val tvGroup: TextView = view.findViewById(R.id.tvChannelGroup)
        val btnLoad: View    = view.findViewById(R.id.btnLoadChannel)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_memory_channel, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val ch = getItem(position)
        holder.tvName.text  = ch.name
        holder.tvFreq.text  = "${"%.4f".format(ch.frequencyHz / 1e6)} MHz"
        holder.tvMode.text  = ch.demodMode
        holder.tvGroup.text = ch.group
        holder.btnLoad.setOnClickListener { onLoad(ch) }
        holder.itemView.setOnLongClickListener { onEdit(ch); true }
    }

    class DiffCallback : DiffUtil.ItemCallback<MemoryChannel>() {
        override fun areItemsTheSame(a: MemoryChannel, b: MemoryChannel) = a.id == b.id
        override fun areContentsTheSame(a: MemoryChannel, b: MemoryChannel) = a == b
    }
}

/**
 * Swipe-to-delete helper. Triggers [onDelete] with the swiped item.
 */
class SwipeToDeleteCallback(
    private val onDelete: (MemoryChannel) -> Unit
) : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {

    override fun onMove(
        rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder
    ) = false

    override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
        val adapter = (vh.itemView.parent as? RecyclerView)?.adapter
            as? MemoryChannelAdapter ?: return
        val channel = adapter.currentList.getOrNull(vh.adapterPosition) ?: return
        onDelete(channel)
    }

    override fun onChildDraw(
        c: android.graphics.Canvas, rv: RecyclerView, vh: RecyclerView.ViewHolder,
        dX: Float, dY: Float, actionState: Int, isActive: Boolean
    ) {
        // Draw red background behind swiped item
        if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
            val itemView = vh.itemView
            val paint = android.graphics.Paint().apply { color = 0xFFCC3333.toInt() }
            if (dX > 0) {
                c.drawRect(itemView.left.toFloat(), itemView.top.toFloat(),
                    itemView.left + dX, itemView.bottom.toFloat(), paint)
            } else {
                c.drawRect(itemView.right + dX, itemView.top.toFloat(),
                    itemView.right.toFloat(), itemView.bottom.toFloat(), paint)
            }
        }
        super.onChildDraw(c, rv, vh, dX, dY, actionState, isActive)
    }
}
