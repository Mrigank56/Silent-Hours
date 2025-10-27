package com.example.silenthours

import androidx.compose.runtime.Composable

@Composable
fun GroupBlockingRuleCard(
    rules: List<BlockingRule>, // replace with the actual type of 'rules'
    onEdit: () -> Unit,
    onDelete: (groupId: String?) -> Unit,
    onToggle: (groupId: String?, enabled: Boolean) -> Unit
) {
    // TODO: Build your UI here
}
