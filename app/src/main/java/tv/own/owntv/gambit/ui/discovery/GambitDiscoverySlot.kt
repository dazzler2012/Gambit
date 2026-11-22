package tv.own.owntv.gambit.ui.discovery

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.R
import tv.own.owntv.core.nav.MainSection
import tv.own.owntv.ui.components.OwnTVIcon

/*
 * Discovery lives in the rail's Sports slot. The sections are Core's (MainSection), so the app cannot add
 * one; instead the rail asks here for the slot's icon and name, and the shell shows Discovery's page there.
 * The Sports Hub itself is left in place (its background score update still runs) but nothing opens it.
 */

/** The slot Discovery takes over. */
val GambitDiscoverySection: MainSection = MainSection.SPORTS

/** Discovery's mark in the rail: a poster grid. */
val GambitDiscoveryIcon: OwnTVIcon = OwnTVIcon.GRID

/** The rail name of the slot. */
@Composable
fun gambitDiscoveryLabel(): String = stringResource(R.string.gambit_discovery_title)

/**
 * The rail's sections with Discovery's slot left out unless the active profile is known to be an adult
 * one: never on a kids profile, and not before the profile has been read.
 */
@Composable
fun gambitDiscoveryRail(visible: Set<MainSection>, vm: DiscoveryViewModel = koinViewModel()): Set<MainSection> {
    val access by vm.access.collectAsStateWithLifecycle()
    return if (access == DiscoveryAccess.OPEN) visible else visible - GambitDiscoverySection
}
