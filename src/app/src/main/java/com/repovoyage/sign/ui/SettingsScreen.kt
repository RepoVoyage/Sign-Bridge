package com.repovoyage.sign.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.repovoyage.sign.R
import com.repovoyage.sign.sentence.LangCode
import androidx.compose.ui.res.stringResource

/** 设置界面：字幕/语音语言、识别模型选择、文本缓存、云端 LLM 凭据 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: SettingsViewModel, onBack: () -> Unit) {
    val selected by vm.selectedLanguages.collectAsStateWithLifecycle()
    val spoken by vm.spokenLanguages.collectAsStateWithLifecycle()
    val ttsEnabled by vm.ttsEnabled.collectAsStateWithLifecycle()
    val cacheEnabled by vm.cacheEnabled.collectAsStateWithLifecycle()
    val selectedModelId by vm.selectedModelId.collectAsStateWithLifecycle()
    val credentials by vm.llmCredentials.collectAsStateWithLifecycle()
    val urlDraft by vm.urlDraft.collectAsState()
    val keyDraft by vm.keyDraft.collectAsState()
    val modelDraft by vm.modelDraft.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.nav_back)) }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ------------------------------------------------ 字幕语言
            SettingsCard(stringResource(R.string.settings_languages_title), stringResource(R.string.settings_languages_hint)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    vm.supportedLanguages.forEach { language ->
                        val index = selected.indexOf(language)
                        FilterChip(
                            selected = index >= 0,
                            onClick = { vm.toggleSelected(language) },
                            label = {
                                Text(
                                    if (index >= 0) "${index + 1}. ${languageDisplayName(language)}"
                                    else languageDisplayName(language),
                                )
                            },
                        )
                    }
                }
            }

            // ------------------------------------------------ 语音语言
            SettingsCard(stringResource(R.string.settings_spoken_title), stringResource(R.string.settings_spoken_hint)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    vm.supportedLanguages.forEach { language ->
                        FilterChip(
                            selected = language in spoken,
                            enabled = language in selected,
                            onClick = { vm.toggleSpoken(language) },
                            label = { Text(languageDisplayName(language)) },
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.settings_tts_switch), Modifier.weight(1f))
                    Switch(checked = ttsEnabled, onCheckedChange = vm::setTtsEnabled)
                }
            }

            // ------------------------------------------------ 识别模型
            SettingsCard(stringResource(R.string.settings_model_title), stringResource(R.string.settings_model_hint)) {
                vm.modelEntries.forEach { (entry, installed) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selectedModelId == entry.id,
                            onClick = { if (installed) vm.selectModel(entry.id) },
                            enabled = installed,
                        )
                        Text(entry.displayName, Modifier.weight(1f))
                        Text(
                            stringResource(if (installed) R.string.model_installed else R.string.model_not_installed),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (installed) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }

            // ------------------------------------------------ 文本缓存
            SettingsCard(stringResource(R.string.settings_cache_title), null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.settings_cache_switch), Modifier.weight(1f))
                    Switch(checked = cacheEnabled, onCheckedChange = vm::setCacheEnabled)
                }
                Text(
                    stringResource(R.string.settings_cache_desc),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // ------------------------------------------------ LLM 凭据
            SettingsCard(stringResource(R.string.settings_llm_title), stringResource(R.string.settings_llm_hint)) {
                Text(
                    stringResource(R.string.settings_llm_cost_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                OutlinedTextField(
                    value = urlDraft,
                    onValueChange = { vm.urlDraft.value = it },
                    label = { Text(stringResource(R.string.llm_base_url_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = keyDraft,
                    onValueChange = { vm.keyDraft.value = it },
                    label = { Text(stringResource(R.string.llm_api_key_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = modelDraft,
                    onValueChange = { vm.modelDraft.value = it },
                    label = { Text(stringResource(R.string.llm_model_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = vm::saveCredentials) { Text(stringResource(R.string.llm_save)) }
                    Text(
                        stringResource(
                            if (credentials.isConfigured) R.string.llm_configured else R.string.llm_not_configured,
                        ),
                        modifier = Modifier.padding(start = 12.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (credentials.isConfigured) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(title: String, hint: String?, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall)
            content()
        }
    }
}
