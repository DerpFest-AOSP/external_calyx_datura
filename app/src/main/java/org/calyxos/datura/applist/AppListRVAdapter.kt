/*
 * SPDX-FileCopyrightText: 2023 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.datura.applist

import android.content.pm.PackageManager
import android.net.NetworkPolicyManager
import android.net.NetworkPolicyManager.POLICY_REJECT_ALL
import android.net.NetworkPolicyManager.POLICY_REJECT_CELLULAR
import android.net.NetworkPolicyManager.POLICY_REJECT_METERED_BACKGROUND
import android.net.NetworkPolicyManager.POLICY_REJECT_VPN
import android.net.NetworkPolicyManager.POLICY_REJECT_WIFI
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch
import org.calyxos.datura.R
import org.calyxos.datura.databinding.RecyclerViewAppListBinding
import org.calyxos.datura.databinding.RecyclerViewHeaderListBinding
import org.calyxos.datura.models.App
import org.calyxos.datura.models.DaturaItem
import org.calyxos.datura.models.Header
import org.calyxos.datura.models.Type
import javax.inject.Inject
import javax.inject.Singleton

class AppListRVAdapter @Inject constructor(
    daturaItemDiffUtil: DaturaItemDiffUtil,
    private val networkPolicyManager: NetworkPolicyManager
) : ListAdapter<DaturaItem, RecyclerView.ViewHolder>(daturaItemDiffUtil) {

    inner class AppViewHolder(val binding: RecyclerViewAppListBinding) :
        RecyclerView.ViewHolder(binding.root)

    inner class HeaderViewHolder(val binding: RecyclerViewHeaderListBinding) :
        RecyclerView.ViewHolder(binding.root)

    @Singleton
    class DaturaItemDiffUtil @Inject constructor() : DiffUtil.ItemCallback<DaturaItem>() {

        override fun areItemsTheSame(oldItem: DaturaItem, newItem: DaturaItem): Boolean {
            return when (oldItem.type) {
                newItem.type -> {
                    if (oldItem.type == Type.APP) {
                        (oldItem as App).packageName == (newItem as App).packageName
                    } else {
                        (oldItem as Header).name == (newItem as Header).name
                    }
                }
                else -> false
            }
        }

        override fun areContentsTheSame(oldItem: DaturaItem, newItem: DaturaItem): Boolean {
            return when (oldItem.type) {
                Type.APP -> {
                    val oldApp = (oldItem as App)
                    val newApp = (newItem as App)
                    when {
                        oldApp.icon != newApp.icon -> false
                        oldApp.name != newApp.name -> false
                        oldApp.packageName != newApp.packageName -> false
                        oldApp.systemApp != newApp.systemApp -> false
                        oldApp.uid != newApp.uid -> false
                        oldApp.requestsInternetPermission != newApp.requestsInternetPermission -> false
                        oldApp.isExpanded != newApp.isExpanded -> false
                        else -> true
                    }
                }
                Type.HEADER -> {
                    (oldItem as Header).name == (newItem as Header).name
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            Type.APP.ordinal -> {
                AppViewHolder(
                    RecyclerViewAppListBinding.inflate(
                        LayoutInflater.from(parent.context),
                        parent,
                        false
                    )
                )
            }

            else -> {
                HeaderViewHolder(
                    RecyclerViewHeaderListBinding.inflate(
                        LayoutInflater.from(parent.context),
                        parent,
                        false
                    )
                )
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is AppViewHolder) {
            onBindAppViewHolder(holder, position)
        } else if (holder is HeaderViewHolder) {
            onBindHeaderViewHolder(holder, position)
        }
    }

    override fun getItemViewType(position: Int): Int {
        return currentList[position].type.ordinal
    }

    private fun onBindHeaderViewHolder(holder: HeaderViewHolder, position: Int) {
        val header = getItem(position) as Header
        holder.binding.header.text = header.name
    }

    private fun onBindAppViewHolder(holder: AppViewHolder, position: Int) {
        val app = getItem(position) as App

        // Map of switches to their policy
        val mapOfSwitchAndPolicy = mapOf(
            holder.binding.mainSwitch to POLICY_REJECT_ALL,
            holder.binding.expandLayout.backgroundSwitch to POLICY_REJECT_METERED_BACKGROUND,
            holder.binding.expandLayout.wifiSwitch to POLICY_REJECT_WIFI,
            holder.binding.expandLayout.mobileSwitch to POLICY_REJECT_CELLULAR,
            holder.binding.expandLayout.vpnSwitch to POLICY_REJECT_VPN
        )

        holder.binding.apply {
            appIcon.background = app.icon.toDrawable(holder.binding.rootView.resources)
            appName.text = app.name

            // Expand layout on root view click
            expandLayout(this, app.isExpanded, app.requestsInternetPermission)
            rootView.setOnClickListener {
                if (it.isVisible && app.requestsInternetPermission) {
                    (currentList[holder.adapterPosition] as App).isExpanded = !app.isExpanded
                    expandLayout(this, app.isExpanded, true)
                }
            }

            // Switches, Checked/0 == Allowed to connect to internet (default)
            val mainSwitchEnabled =
                (networkPolicyManager.getUidPolicy(app.uid) and POLICY_REJECT_ALL) == 0

            mapOfSwitchAndPolicy.forEach { (switch, policy) ->
                switch.apply {
                    setOnCheckedChangeListener(null)

                    // Ensure main switch is enabled before enabling child switches
                    isEnabled = if (switch == mainSwitch) {
                        app.requestsInternetPermission
                    } else {
                        app.requestsInternetPermission && mainSwitchEnabled
                    }

                    isChecked =
                        (networkPolicyManager.getUidPolicy(app.uid) and policy) == 0 &&
                        app.requestsInternetPermission

                    setOnCheckedChangeListener { view, isChecked ->
                        if (view.isVisible) {
                            if (isChecked) {
                                networkPolicyManager.removeUidPolicy(app.uid, policy)
                            } else {
                                networkPolicyManager.addUidPolicy(app.uid, policy)
                            }

                            // Disable/Enable child buttons if main switch was toggled
                            if (switch == mainSwitch) {
                                mapOfSwitchAndPolicy.filter { it.key != mainSwitch }.forEach {
                                    it.key.isEnabled = isChecked
                                }
                            }

                            // Reflect appropriate settings status
                            updateSettingsText(
                                settingsMode,
                                mapOfSwitchAndPolicy.keys,
                                app.requestsInternetPermission
                            )
                        }
                    }
                }
            }

            updateSettingsText(
                settingsMode,
                mapOfSwitchAndPolicy.keys,
                app.requestsInternetPermission
            )
        }
    }

    private fun expandLayout(
        binding: RecyclerViewAppListBinding,
        expand: Boolean,
        reqInternetPerm: Boolean
    ) {
        binding.apply {
            expandButton.apply {
                isEnabled = reqInternetPerm
                isClickable = false // Keep false, consumes touch event otherwise
            }

            binding.expandLayout.apply {
                if (!expand) {
                    expandButton.icon =
                        ContextCompat.getDrawable(rootView.context, R.drawable.ic_arrow_down)
                    expandLayout.root.visibility = View.GONE
                } else {
                    expandButton.icon =
                        ContextCompat.getDrawable(binding.rootView.context, R.drawable.ic_arrow_up)
                    expandLayout.root.visibility = View.VISIBLE

                    // Show specific rows only if device has that feature
                    val packageManager = rootView.context.packageManager
                    if (packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI)) {
                        wifiLayout.visibility = View.VISIBLE
                    }
                    if (packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
                        mobileLayout.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    private fun updateSettingsText(
        settingsMode: TextView,
        switches: Set<MaterialSwitch>,
        reqInternetPerm: Boolean
    ) {
        val context = settingsMode.context
        if (reqInternetPerm) {
            if (switches.all { it.isChecked }) {
                settingsMode.text = context.getString(R.string.default_settings)
            } else {
                settingsMode.text = context.getString(R.string.custom_settings)
            }
        } else {
            settingsMode.text = context.getString(R.string.no_internet)
        }
    }
}
