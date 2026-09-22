package com.binarybeast.linuxrunner

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.binarybeast.linuxrunner.databinding.ItemDistroBinding

class DistroListAdapter(
    private val distros: List<Distro>,
    private val storage: DistroStorageManager,
    private val onClick: (Distro) -> Unit,
    private val onLongClick: (Distro) -> Unit = {}
) : RecyclerView.Adapter<DistroListAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemDistroBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDistroBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val distro = distros[position]
        holder.binding.distroName.text = distro.displayName
        holder.binding.distroDescription.text = distro.description

        holder.binding.distroSizeOrStatus.text = if (storage.isInstalled(distro)) {
            "مثبت — ${storage.currentUsageMb(distro)} MB مستخدمة"
        } else {
            "تحميل تقريبي: ${distro.approxDownloadSizeMb} MB"
        }

        holder.binding.root.setOnClickListener { onClick(distro) }
        holder.binding.root.setOnLongClickListener {
            onLongClick(distro)
            true
        }
    }

    override fun getItemCount() = distros.size
}
