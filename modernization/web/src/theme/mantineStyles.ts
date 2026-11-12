// Only the Mantine styles the app uses, instead of @mantine/core/styles.css, to stay inside the CSS budget.
// Order matters: the global files first, then the shared building blocks, then the components that extend them
// (https://mantine.dev/styles/css-files-list/). A component added to the app needs its file here.
import '@mantine/core/styles/baseline.css';
import '@mantine/core/styles/default-css-variables.css';
import '@mantine/core/styles/global.css';
import '@mantine/core/styles/ScrollArea.css';
import '@mantine/core/styles/UnstyledButton.css';
import '@mantine/core/styles/VisuallyHidden.css';
import '@mantine/core/styles/Paper.css';
import '@mantine/core/styles/Popover.css';
import '@mantine/core/styles/CloseButton.css';
import '@mantine/core/styles/Group.css';
import '@mantine/core/styles/Loader.css';
import '@mantine/core/styles/Overlay.css';
import '@mantine/core/styles/ModalBase.css';
import '@mantine/core/styles/Input.css';
import '@mantine/core/styles/InlineInput.css';
import '@mantine/core/styles/Avatar.css';
import '@mantine/core/styles/Badge.css';
import '@mantine/core/styles/Button.css';
import '@mantine/core/styles/Center.css';
import '@mantine/core/styles/Kbd.css';
import '@mantine/core/styles/Menu.css';
import '@mantine/core/styles/Modal.css';
import '@mantine/core/styles/PasswordInput.css';
import '@mantine/core/styles/Radio.css';
import '@mantine/core/styles/Stack.css';
import '@mantine/core/styles/Switch.css';
import '@mantine/core/styles/Table.css';
import '@mantine/core/styles/Text.css';
import '@mantine/core/styles/ThemeIcon.css';
import '@mantine/core/styles/Title.css';
