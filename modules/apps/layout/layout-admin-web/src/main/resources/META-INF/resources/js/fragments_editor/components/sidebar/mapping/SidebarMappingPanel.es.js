import Component from 'metal-component';
import {Config} from 'metal-state';
import Soy from 'metal-soy';

import 'frontend-js-web/liferay/compat/modal/Modal.es';
import {OPEN_ASSET_TYPE_DIALOG, UPDATE_HIGHLIGHT_MAPPING_STATUS} from '../../../actions/actions.es';
import {Store} from '../../../store/store.es';
import templates from './SidebarMappingPanel.soy';

/**
 * SidebarMappingPanel
 */
class SidebarMappingPanel extends Component {

	/**
	 * @inheritDoc
	 * @review
	 */
	disposed() {
		this.store.dispatchAction(
			UPDATE_HIGHLIGHT_MAPPING_STATUS,
			{
				highlightMapping: false
			}
		);
	}

	/**
	 * Callback executed on highlight mapping checkbox click
	 * @param {MouseEvent} event
	 * @private
	 * @review
	 */
	_handleHighlightMappingCheckboxChange(event) {
		this.store.dispatchAction(
			UPDATE_HIGHLIGHT_MAPPING_STATUS,
			{
				highlightMapping: Boolean(event.delegateTarget.checked)
			}
		);
	}

	/**
	 * Open asset type selection dialog
	 * @private
	 * @review
	 */
	_handleSelectAssetTypeButtonClick() {
		this.store.dispatchAction(OPEN_ASSET_TYPE_DIALOG);
	}

}

/**
 * State definition.
 * @review
 * @static
 * @type {!Object}
 */

SidebarMappingPanel.STATE = {

	/**
	 * If true, editable values should be highlighted.
	 * @default false
	 * @instance
	 * @memberOf FragmentsEditor
	 * @private
	 * @review
	 * @type {boolean}
	 */
	highlightMapping: Config.bool()
		.value(false),

	/**
	 * Selected mapping type label
	 * @default {}
	 * @instance
	 * @memberOf SidebarMappingPanel
	 * @review
	 * @type {{
	 *   subtype: {
	 *   	id: !string,
	 *   	label: !string
	 *   },
	 *   type: {
	 *   	id: !string,
	 *   	label: !string
	 *   }
	 * }}
	 */
	selectedMappingTypes: Config
		.shapeOf(
			{
				subtype: Config.shapeOf(
					{
						id: Config.string().required(),
						label: Config.string().required()
					}
				),
				type: Config.shapeOf(
					{
						id: Config.string().required(),
						label: Config.string().required()
					}
				)
			}
		)
		.value({}),

	/**
	 * Store instance
	 * @default undefined
	 * @instance
	 * @memberOf SidebarMappingPanel
	 * @review
	 * @type {Store}
	 */
	store: Config.instanceOf(Store)
};

Soy.register(SidebarMappingPanel, templates);

export {SidebarMappingPanel};
export default SidebarMappingPanel;